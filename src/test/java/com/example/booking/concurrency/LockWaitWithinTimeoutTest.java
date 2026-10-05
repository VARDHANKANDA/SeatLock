package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.booking.IntegrationTestSupport;

/** Legitimate short contention must not be turned into failures: a wait shorter than the timeout succeeds. */
@TestPropertySource(properties = "app.locking.lock-timeout-ms=20000")
class LockWaitWithinTimeoutTest extends IntegrationTestSupport {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aRequestThatWaitsForALockLessThanTheTimeoutStillSucceeds() throws Exception {
        String token = bearerFor(saveUser("alice@example.com", Role.USER));
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<MvcResult>> pending = new AtomicReference<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("select id from seats where id = ? for update", Long.class, seat.getId());
                pending.set(executor.submit(() -> mockMvc.perform(post("/api/v1/bookings")
                        .header(AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId())))).andReturn()));
                await().atMost(Duration.ofSeconds(30)).until(this::bookingIsWaitingForSeatLock);
            });

            MvcResult result = pending.get().get(30, TimeUnit.SECONDS);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(jdbc.queryForObject("select count(*) from bookings where status = 'CONFIRMED'", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean bookingIsWaitingForSeatLock() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity "
                + "where wait_event_type = 'Lock' and query ilike '%from seats%for no key update%'", Integer.class) > 0;
    }
}
