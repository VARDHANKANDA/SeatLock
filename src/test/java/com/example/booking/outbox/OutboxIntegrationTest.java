package com.example.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.dto.CreateHoldRequest;
import com.example.booking.entity.Event;
import com.example.booking.entity.OutboxEvent;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import com.example.booking.repository.OutboxEventRepository;
import com.example.booking.security.AuthenticatedUser;
import com.example.booking.service.BookingResult;
import com.example.booking.service.BookingService;
import com.example.booking.service.ReservationHoldService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "app.outbox.batch-size=10")
class OutboxIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private ReservationHoldService holdService;
    @Autowired
    private OutboxProcessor processor;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private NotificationService notifications;

    private User user;
    private Event event;

    @BeforeEach
    void setUp() {
        user = saveUser("user@example.com", Role.USER);
        event = saveEvent();
    }

    @Test
    void confirmedBookingAndItsOutboxEventCommitTogether() throws Exception {
        Seat seat = saveSeat(event, "A1");

        long bookingId = book(seat).booking().id();

        assertThat(rowCount("select count(*) from outbox_event")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "select aggregate_type, aggregate_id, event_type, status, retry_count from outbox_event");
        assertThat(row).containsEntry("aggregate_type", "BOOKING").containsEntry("aggregate_id", bookingId)
                .containsEntry("event_type", "BOOKING_CONFIRMED").containsEntry("status", "PENDING")
                .containsEntry("retry_count", 0);
    }

    @Test
    void payloadCarriesBusinessIdentifiersAndNoCredentials() throws Exception {
        Seat seat = saveSeat(event, "A1");
        long bookingId = book(seat).booking().id();

        JsonNode payload = objectMapper.readTree(
                jdbc.queryForObject("select payload::text from outbox_event", String.class));

        assertThat(payload.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(payload.get("userId").asLong()).isEqualTo(user.getId());
        assertThat(payload.get("eventId").asLong()).isEqualTo(event.getId());
        assertThat(payload.get("seatNumber").asText()).isEqualTo("A1");
        assertThat(payload.toString().toLowerCase()).doesNotContain("password", "token", "secret", "hash", "email");
    }

    @Test
    void rolledBackBookingLeavesNoBookingNoSeatChangeAndNoOutboxEvent() {
        Seat seat = saveSeat(event, "A1");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            book(seat);
            throw new IllegalStateException("force rollback after the booking was written");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rowCount("select count(*) from bookings")).isZero();
        assertThat(rowCount("select count(*) from outbox_event")).isZero();
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat.getId()))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void rejectedBookingRecordsNoAdditionalEvent() {
        Seat seat = saveSeat(event, "A1");
        book(seat);

        assertThatThrownBy(() -> book(seat)).isInstanceOf(RuntimeException.class);

        assertThat(rowCount("select count(*) from outbox_event")).isEqualTo(1);
    }

    @Test
    void processorDeliversEventAndMarksItProcessed() {
        book(saveSeat(event, "A1"));

        int delivered = processor.processBatch();

        assertThat(delivered).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select status, processed_at, last_error from outbox_event");
        assertThat(row.get("status")).isEqualTo("PROCESSED");
        assertThat(row.get("processed_at")).isNotNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(jdbc.queryForObject("select message from notification_log", String.class))
                .contains("confirmed").contains("A1");
        assertThat(processor.processBatch()).isZero();
    }

    @Test
    void bookingStaysConfirmedWhenProcessingFailsAndTheEventRemainsRetryable() {
        book(saveSeat(event, "A1"));
        doThrow(new IllegalStateException("notification backend down")).doCallRealMethod()
                .when(notifications).handle(any());

        int delivered = processor.processBatch();

        assertThat(delivered).isZero();
        assertThat(rowCount("select count(*) from bookings where status = 'CONFIRMED'")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "select status, retry_count, last_error, next_attempt_at > now() as backed_off from outbox_event");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("notification backend down");
        assertThat(row.get("backed_off")).isEqualTo(true);
        assertThat(rowCount("select count(*) from notification_log")).isZero();

        assertThat(processor.processBatch()).as("not due yet, so no tight retry loop").isZero();
        makeDue();
        assertThat(processor.processBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox_event", String.class)).isEqualTo("PROCESSED");
        assertThat(rowCount("select count(*) from notification_log")).isEqualTo(1);
    }

    @Test
    void aFailingEventDoesNotBlockTheEventsAroundItInTheSameBatch() {
        for (int i = 1; i <= 3; i++) {
            book(saveSeat(event, "S" + i));
        }
        List<Long> ids = jdbc.queryForList("select id from outbox_event order by id", Long.class);
        long poisonId = ids.get(1);
        doThrow(new IllegalStateException("poison message")).when(notifications)
                .handle(argThat(outboxEvent -> outboxEvent.getId() == poisonId));

        int delivered = processor.processBatch();

        assertThat(delivered).isEqualTo(2);
        assertThat(jdbc.queryForList("select id from outbox_event where status = 'PROCESSED' order by id", Long.class))
                .containsExactly(ids.get(0), ids.get(2));
        Map<String, Object> poison = jdbc.queryForMap(
                "select status, retry_count, last_error, processed_at from outbox_event where id = ?", poisonId);
        assertThat(poison.get("status")).isEqualTo("PENDING");
        assertThat(poison.get("retry_count")).isEqualTo(1);
        assertThat((String) poison.get("last_error")).contains("poison message");
        assertThat(poison.get("processed_at")).as("failed delivery is not marked processed").isNull();
    }

    @Test
    void eventIsMarkedFailedOnceRetriesAreExhaustedAndNoLongerPicked() {
        book(saveSeat(event, "A1"));
        doThrow(new IllegalStateException("permanent failure")).when(notifications).handle(any());

        for (int attempt = 1; attempt <= 5; attempt++) {
            makeDue();
            processor.processBatch();
        }

        Map<String, Object> row = jdbc.queryForMap("select status, retry_count from outbox_event");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("retry_count")).isEqualTo(5);
        makeDue();
        assertThat(processor.processBatch()).isZero();
        assertThat(rowCount("select count(*) from bookings where status = 'CONFIRMED'")).isEqualTo(1);
    }

    @Test
    void redeliveringAnEventHasNoDuplicateSideEffect() {
        book(saveSeat(event, "A1"));
        OutboxEvent stored = outboxRepository.findAll().get(0);

        notifications.handle(stored);
        notifications.handle(stored);
        notifications.handle(stored);

        assertThat(rowCount("select count(*) from notification_log")).isEqualTo(1);
    }

    @Test
    void concurrentWorkersDeliverEveryEventExactlyOnce() throws Exception {
        for (int i = 0; i < 25; i++) {
            book(saveSeat(event, "S" + i));
        }

        List<Callable<Integer>> workers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            workers.add(() -> {
                int total = 0;
                int delivered;
                do {
                    delivered = processor.processBatch();
                    total += delivered;
                } while (delivered > 0);
                return total;
            });
        }
        int deliveredByWorkers = 0;
        var executor = Executors.newFixedThreadPool(workers.size());
        try {
            for (var future : executor.invokeAll(workers)) {
                deliveredByWorkers += future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(deliveredByWorkers).isEqualTo(25);
        assertThat(rowCount("select count(*) from outbox_event where status = 'PROCESSED'")).isEqualTo(25);
        assertThat(rowCount("select count(*) from notification_log")).isEqualTo(25);
        verify(notifications, times(25)).handle(any());
    }

    @Test
    void eachBatchIsBoundedByTheConfiguredSize() {
        for (int i = 0; i < 25; i++) {
            book(saveSeat(event, "S" + i));
        }

        assertThat(processor.processBatch()).isEqualTo(10);
        assertThat(rowCount("select count(*) from outbox_event where status = 'PENDING'")).isEqualTo(15);
        assertThat(processor.processBatch()).isEqualTo(10);
        assertThat(processor.processBatch()).isEqualTo(5);
    }

    @Test
    void cancellingABookingRecordsABookingCancelledEvent() {
        Seat seat = saveSeat(event, "A1");
        long bookingId = book(seat).booking().id();

        bookingService.cancel(bookingId, new AuthenticatedUser(user.getId(), Role.USER));

        assertThat(eventTypes()).containsExactlyInAnyOrder("BOOKING_CONFIRMED", "BOOKING_CANCELLED");
        processor.processBatch();
        assertThat(jdbc.queryForList("select message from notification_log", String.class))
                .anyMatch(message -> message.contains("cancelled"));
    }

    @Test
    void holdLifecycleRecordsHeldCancelledExpiredAndConfirmedEvents() {
        Seat first = saveSeat(event, "A1");
        Seat second = saveSeat(event, "A2");
        Seat third = saveSeat(event, "A3");
        AuthenticatedUser actor = new AuthenticatedUser(user.getId(), Role.USER);

        long cancelled = holdService.createHold(user.getId(), new CreateHoldRequest(event.getId(), first.getId())).holdId();
        holdService.cancel(cancelled, actor);
        long lapsed = holdService.createHold(user.getId(), new CreateHoldRequest(event.getId(), second.getId())).holdId();
        jdbc.update("update reservation_holds set expires_at = now() - interval '1 minute' where id = ?", lapsed);
        holdService.expire(lapsed);
        long confirmed = holdService.createHold(user.getId(), new CreateHoldRequest(event.getId(), third.getId())).holdId();
        holdService.confirm(confirmed, actor);

        assertThat(eventTypes()).containsExactlyInAnyOrder(
                "RESERVATION_HELD", "RESERVATION_CANCELLED",
                "RESERVATION_HELD", "RESERVATION_EXPIRED",
                "RESERVATION_HELD", "BOOKING_CONFIRMED");
        while (processor.processBatch() > 0) {
            // drain
        }
        assertThat(rowCount("select count(*) from notification_log")).isEqualTo(6);
    }

    @Test
    void processingUpdatesOutboxMetrics() {
        book(saveSeat(event, "A1"));
        double processed = counter("outbox.processed");
        assertThat(meterRegistry.get("outbox.pending").gauge().value()).isEqualTo(1.0);

        processor.processBatch();

        assertThat(counter("outbox.processed")).isEqualTo(processed + 1);
        assertThat(meterRegistry.get("outbox.pending").gauge().value()).isZero();
    }

    private BookingResult book(Seat seat) {
        return bookingService.createBooking(user.getId(), new CreateBookingRequest(event.getId(), seat.getId()), null);
    }

    private void makeDue() {
        jdbc.update("update outbox_event set next_attempt_at = now() - interval '1 second'");
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("select event_type from outbox_event", String.class);
    }

    private int rowCount(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private double counter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
