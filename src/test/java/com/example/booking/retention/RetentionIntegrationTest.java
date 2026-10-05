package com.example.booking.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(properties = "app.retention.batch-size=10")
class RetentionIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RetentionCleaner cleaner;
    @Autowired
    private RetentionBatches batches;

    private User user;

    @BeforeEach
    void setUp() {
        user = saveUser("user@example.com", Role.USER);
    }

    @Test
    void deletesOnlyProcessedEventsOlderThanTheRetentionAndTheirNotifications() {
        long oldProcessed = insertOutbox("PROCESSED", 20);
        long recentProcessed = insertOutbox("PROCESSED", 1);
        long oldPending = insertOutbox("PENDING", 20);
        long oldFailed = insertOutbox("FAILED", 20);
        insertNotification(oldProcessed);
        insertNotification(recentProcessed);

        RetentionCleaner.Result result = cleaner.cleanOnce();

        assertThat(result).isEqualTo(new RetentionCleaner.Result(1, 0, 0));
        assertThat(outboxIds()).containsExactlyInAnyOrder(recentProcessed, oldPending, oldFailed);
        assertThat(count("select count(*) from notification_log")).as("kept event keeps its notification").isEqualTo(1);
        assertThat(count("select count(*) from notification_log where outbox_event_id = " + recentProcessed)).isEqualTo(1);
    }

    @Test
    void pendingAndRetryableEventsAreNeverDeletedHoweverOldTheyAre() {
        insertOutbox("PENDING", 365);
        insertOutbox("PENDING", 40);
        jdbc.update("update outbox_event set retry_count = 3, last_error = 'boom', next_attempt_at = now() + interval '1 hour'");

        cleaner.cleanOnce();

        assertThat(count("select count(*) from outbox_event where status = 'PENDING'")).isEqualTo(2);
    }

    @Test
    void failedEventsAreKeptByDefault() {
        insertOutbox("FAILED", 400);

        cleaner.cleanOnce();

        assertThat(count("select count(*) from outbox_event where status = 'FAILED'")).isEqualTo(1);
    }

    @Test
    void eachBatchIsBoundedAndTheCleanerWorksThroughTheBacklog() {
        IntStream.range(0, 25).forEach(i -> insertOutbox("PROCESSED", 30));
        Instant cutoff = Instant.now();

        int firstBatch = batches.deleteProcessedOutbox(cutoff, 10);

        assertThat(firstBatch).isEqualTo(10);
        assertThat(count("select count(*) from outbox_event")).isEqualTo(15);
        assertThat(cleaner.cleanOnce().processedOutbox()).isEqualTo(15);
        assertThat(count("select count(*) from outbox_event")).isZero();
    }

    @Test
    void concurrentCleanersDeleteEachRowExactlyOnce() throws Exception {
        IntStream.range(0, 100).forEach(i -> insertOutbox("PROCESSED", 30));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<RetentionCleaner.Result>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<RetentionCleaner.Result> run = cleaner::cleanOnce;
                runs.add(executor.submit(run));
            }
            int deleted = 0;
            for (Future<RetentionCleaner.Result> run : runs) {
                deleted += run.get().processedOutbox();
            }

            assertThat(deleted).isEqualTo(100);
            assertThat(count("select count(*) from outbox_event")).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oldIdempotencyKeysAreForgottenAndRecentOnesAreKept() throws Exception {
        String token = bearerFor(user);
        Event event = saveEvent();
        Seat first = saveSeat(event, "A1");
        Seat second = saveSeat(event, "A2");
        book(token, event, first, "old-key").andExpect(status().isCreated());
        book(token, event, second, "recent-key").andExpect(status().isCreated());
        jdbc.update("update idempotency_records set created_at = now() - interval '2 days' "
                + "where idempotency_key = 'old-key'");

        RetentionCleaner.Result result = cleaner.cleanOnce();

        assertThat(result.idempotencyKeys()).isEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from idempotency_records", String.class))
                .containsExactly("recent-key");
        assertThat(count("select count(*) from bookings")).as("bookings are never touched").isEqualTo(2);
    }

    @Test
    void aForgottenKeyIsTreatedAsANewRequestAndARememberedOneIsStillDeduplicated() throws Exception {
        String token = bearerFor(user);
        Event event = saveEvent();
        Seat first = saveSeat(event, "A1");
        Seat second = saveSeat(event, "A2");
        book(token, event, first, "reused-key").andExpect(status().isCreated());

        book(token, event, second, "reused-key").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        jdbc.update("update idempotency_records set created_at = now() - interval '2 days'");
        cleaner.cleanOnce();

        book(token, event, second, "reused-key").andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions book(String token, Event event, Seat seat, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/bookings").header(AUTHORIZATION, token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId())));
        return mockMvc.perform(request);
    }

    private long insertOutbox(String status, int ageDays) {
        return jdbc.queryForObject("""
                insert into outbox_event (aggregate_type, aggregate_id, event_type, payload, status,
                                          created_at, processed_at, retry_count)
                values ('BOOKING', 1, 'BOOKING_CONFIRMED', '{}'::jsonb, ?, now() - make_interval(days => ?),
                        case when ? = 'PROCESSED' then now() - make_interval(days => ?) end, 0)
                returning id
                """, Long.class, status, ageDays, status, ageDays);
    }

    private void insertNotification(long outboxEventId) {
        jdbc.update("insert into notification_log (outbox_event_id, user_id, event_type, message) "
                + "values (?, ?, 'BOOKING_CONFIRMED', 'hello')", outboxEventId, user.getId());
    }

    private List<Long> outboxIds() {
        return jdbc.queryForList("select id from outbox_event", Long.class);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
