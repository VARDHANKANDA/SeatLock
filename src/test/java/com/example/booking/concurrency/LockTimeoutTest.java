package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A stalled transaction holds a row lock (an uncommitted {@code SELECT ... FOR UPDATE} in the test). Requests that
 * need that row must give up after the configured lock timeout with a controlled 503, and must leave no trace.
 */
@TestPropertySource(properties = "app.locking.lock-timeout-ms=500")
class LockTimeoutTest extends IntegrationTestSupport {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String alice;
    private String bob;
    private Event event;
    private Seat seat;

    @BeforeEach
    void setUp() {
        alice = bearerFor(saveUser("alice@example.com", Role.USER));
        bob = bearerFor(saveUser("bob@example.com", Role.USER));
        event = saveEvent();
        seat = saveSeat(event, "A1");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void bookingGivesUpWithAControlledResponseAndLeavesNoTrace() throws Exception {
        MvcResult result = whileHolding("select id from seats where id = ? for update", seat.getId(),
                () -> mockMvc.perform(booking(alice, seat, null)).andReturn());

        assertBusy(result);
        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(count("select count(*) from outbox_event")).isZero();
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        mockMvc.perform(booking(alice, seat, null)).andExpect(status().isCreated());
    }

    @Test
    void holdingASeatGivesUpWithoutCreatingAHold() throws Exception {
        MvcResult result = whileHolding("select id from seats where id = ? for update", seat.getId(),
                () -> mockMvc.perform(holdRequest(alice, seat)).andReturn());

        assertBusy(result);
        assertThat(count("select count(*) from reservation_holds")).isZero();
        assertThat(count("select count(*) from outbox_event")).isZero();
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
        mockMvc.perform(holdRequest(alice, seat)).andExpect(status().isCreated());
    }

    @Test
    void confirmingAHoldGivesUpAndTheHoldStaysUsable() throws Exception {
        long holdId = createHold(alice, seat);

        MvcResult result = whileHolding("select id from seats where id = ? for update", seat.getId(),
                () -> mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/confirm")
                        .header(AUTHORIZATION, alice)).andReturn());

        assertBusy(result);
        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(seatStatus(seat)).isEqualTo("HELD");
        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/confirm").header(AUTHORIZATION, alice))
                .andExpect(status().isCreated());
    }

    @Test
    void cancellingAHoldGivesUpAndTheHoldStaysActive() throws Exception {
        long holdId = createHold(alice, seat);

        MvcResult result = whileHolding("select id from seats where id = ? for update", seat.getId(),
                () -> mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/cancel")
                        .header(AUTHORIZATION, alice)).andReturn());

        assertBusy(result);
        assertThat(count("select count(*) from reservation_holds where status = 'HELD'")).isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("HELD");
        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isOk());
    }

    @Test
    void cancellingABookingGivesUpAndTheBookingStaysConfirmed() throws Exception {
        long bookingId = createBooking(alice, seat);

        MvcResult result = whileHolding("select id from bookings where id = ? for update", bookingId,
                () -> mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel")
                        .header(AUTHORIZATION, alice)).andReturn());

        assertBusy(result);
        assertThat(count("select count(*) from bookings where status = 'CONFIRMED'")).isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("BOOKED");
        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isOk());
    }

    @Test
    void waitingOnAnIdempotencyKeyIsBoundedToo() throws Exception {
        Long aliceId = userRepository.findByEmail("alice@example.com").orElseThrow().getId();

        MvcResult result = whileHoldingUncommitted(
                () -> jdbc.update("insert into idempotency_records (user_id, idempotency_key) values (?, 'retry-1')",
                        aliceId),
                () -> mockMvc.perform(booking(alice, seat, "retry-1")).andReturn());

        assertBusy(result);
        assertThat(count("select count(*) from bookings")).isZero();
        assertThat(count("select count(*) from idempotency_records")).isZero();
        mockMvc.perform(booking(alice, seat, "retry-1")).andExpect(status().isCreated());
    }

    @Test
    void theTimeoutIsLocalToBookingTransactions() throws Exception {
        mockMvc.perform(booking(alice, seat, null)).andExpect(status().isCreated());
        mockMvc.perform(booking(bob, seat, null)).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("0");
    }

    private void assertBusy(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("code").asText()).isEqualTo("RESOURCE_BUSY");
        assertThat(body).doesNotContain("lock timeout", "55P03", "SQL", "Exception", "org.");
    }

    private <T> T whileHolding(String lockSql, Long id, Callable<T> request) throws Exception {
        return whileHoldingUncommitted(() -> jdbc.queryForObject(lockSql, Long.class, id), request);
    }

    /** Runs {@code lockAction} in an open transaction, runs the request on another thread, then rolls back. */
    private <T> T whileHoldingUncommitted(Runnable lockAction, Callable<T> request) throws Exception {
        Object[] outcome = new Object[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            lockAction.run();
            long started = System.nanoTime();
            try {
                outcome[0] = executor.submit(request).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("gave up after the timeout, not hung")
                    .isLessThan(Duration.ofSeconds(10));
            tx.setRollbackOnly();
        });
        @SuppressWarnings("unchecked")
        T result = (T) outcome[0];
        return result;
    }

    private MockHttpServletRequestBuilder booking(String token, Seat forSeat, String idempotencyKey) {
        MockHttpServletRequestBuilder request = post("/api/v1/bookings").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", forSeat.getId())));
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    private MockHttpServletRequestBuilder holdRequest(String token, Seat forSeat) {
        return post("/api/v1/bookings/holds").header(AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", forSeat.getId())));
    }

    private long createBooking(String token, Seat forSeat) throws Exception {
        String body = mockMvc.perform(booking(token, forSeat, null)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createHold(String token, Seat forSeat) throws Exception {
        String body = mockMvc.perform(holdRequest(token, forSeat)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").isNumber()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("holdId").asLong();
    }

    private String seatStatus(Seat forSeat) {
        return jdbc.queryForObject("select status from seats where id = ?", String.class, forSeat.getId());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
