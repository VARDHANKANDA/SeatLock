package com.example.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Constraints and indexes added by the V3 migration, checked against a real PostgreSQL. */
class HoldAndOutboxSchemaTest extends IntegrationTestSupport {

    private User user;
    private Event event;
    private Seat seat;

    @BeforeEach
    void setUp() {
        user = saveUser("user@example.com", Role.USER);
        event = saveEvent();
        seat = saveSeat(event, "A1");
    }

    @Test
    void everyMigrationIsAppliedInOrder() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4");
    }

    @Test
    void retentionIndexesExistAndThePartialOnesTargetOnlyFinishedRows() {
        List<String> definitions = jdbc.queryForList("select indexdef from pg_indexes where indexname in "
                + "('idx_outbox_processed_at', 'idx_outbox_failed_created', 'idx_idempotency_created') order by indexname",
                String.class);

        assertThat(definitions).hasSize(3);
        assertThat(definitions).filteredOn(definition -> definition.contains("outbox_event"))
                .hasSize(2).allSatisfy(definition -> assertThat(definition).contains("WHERE"));
    }

    @Test
    void seatsMayBeHeld() {
        jdbc.update("update seats set status = 'HELD' where id = ?", seat.getId());

        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat.getId()))
                .isEqualTo("HELD");
    }

    @Test
    void aSeatCanHaveOnlyOneActiveHold() {
        insertHold("HELD");

        assertThatThrownBy(() -> insertHold("HELD"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_holds_active_seat");
    }

    @Test
    void finishedHoldsAreKeptAsHistoryAndDoNotBlockNewHolds() {
        insertHold("EXPIRED");
        insertHold("CANCELLED");
        insertHold("EXPIRED");
        insertHold("HELD");

        assertThat(jdbc.queryForObject("select count(*) from reservation_holds where seat_id = ?", Integer.class,
                seat.getId())).isEqualTo(4);
    }

    @Test
    void convertedHoldsMustReferenceABookingAndOthersMustNot() {
        assertThatThrownBy(() -> insertHold("CONVERTED"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_holds_booking");

        jdbc.update("insert into bookings (user_id, event_id, seat_id, status) values (?, ?, ?, 'CONFIRMED')",
                user.getId(), event.getId(), seat.getId());
        Long bookingId = jdbc.queryForObject("select id from bookings", Long.class);
        jdbc.update("insert into reservation_holds (user_id, event_id, seat_id, status, expires_at, booking_id) "
                + "values (?, ?, ?, 'CONVERTED', now(), ?)", user.getId(), event.getId(), seat.getId(), bookingId);
        assertThatThrownBy(() -> jdbc.update(
                "insert into reservation_holds (user_id, event_id, seat_id, status, expires_at, booking_id) "
                        + "values (?, ?, ?, 'EXPIRED', now(), ?)", user.getId(), event.getId(), seat.getId(), bookingId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void holdEventMustMatchTheSeatsEventAndStatusMustBeKnown() {
        Event other = saveEvent();

        assertThatThrownBy(() -> jdbc.update(
                "insert into reservation_holds (user_id, event_id, seat_id, status, expires_at) "
                        + "values (?, ?, ?, 'HELD', now())", user.getId(), other.getId(), seat.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_holds_seat_event");
        assertThatThrownBy(() -> insertHold("PENDING")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxRowsDefaultToPendingAndEnforceConsistency() {
        insertOutbox("PENDING");

        assertThat(jdbc.queryForObject("select retry_count from outbox_event", Integer.class)).isZero();
        assertThatThrownBy(() -> insertOutbox("PROCESSED"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_outbox_processed_at");
        assertThatThrownBy(() -> insertOutbox("DONE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update outbox_event set retry_count = -1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOutboxEventHasAtMostOneNotification() {
        insertOutbox("PENDING");
        Long outboxId = jdbc.queryForObject("select id from outbox_event", Long.class);
        String insert = "insert into notification_log (outbox_event_id, user_id, event_type, message) "
                + "values (?, ?, 'BOOKING_CONFIRMED', 'hello')";
        jdbc.update(insert, outboxId, user.getId());

        assertThatThrownBy(() -> jdbc.update(insert, outboxId, user.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_notification_outbox_event");
    }

    @Test
    void partialIndexesTargetOnlyTheRowsTheWorkersScan() {
        List<String> definitions = jdbc.queryForList(
                "select indexdef from pg_indexes where indexname in ('idx_holds_expiry', 'idx_outbox_due', 'uq_holds_active_seat')",
                String.class);

        assertThat(definitions).hasSize(3).allSatisfy(definition -> assertThat(definition).contains("WHERE"));
    }

    private void insertHold(String status) {
        jdbc.update("insert into reservation_holds (user_id, event_id, seat_id, status, expires_at) "
                + "values (?, ?, ?, ?, now() + interval '5 minutes')", user.getId(), event.getId(), seat.getId(), status);
    }

    private void insertOutbox(String status) {
        jdbc.update("insert into outbox_event (aggregate_type, aggregate_id, event_type, payload, status) "
                + "values ('BOOKING', 1, 'BOOKING_CONFIRMED', '{}'::jsonb, ?)", status);
    }
}
