package com.example.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DatabaseConstraintsTest extends IntegrationTestSupport {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

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
    void migrationsCreateExpectedIndexes() {
        List<String> indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String.class);

        assertThat(indexes).contains("uq_bookings_confirmed_seat", "idx_bookings_user_created", "idx_bookings_event",
                "idx_bookings_seat", "idx_events_event_date_time", "idx_seats_event_status", "uq_users_email",
                "uq_seats_event_seat_number", "uq_idempotency_user_key");
    }

    @Test
    void secondConfirmedBookingForSameSeatIsRejected() {
        insertBooking("CONFIRMED");

        assertThatThrownBy(() -> insertBooking("CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_bookings_confirmed_seat");
    }

    @Test
    void cancelledBookingsAreHistoryAndDoNotBlockRebooking() {
        insertBooking("CANCELLED");
        insertBooking("CANCELLED");
        insertBooking("CONFIRMED");

        assertThat(jdbc.queryForObject("select count(*) from bookings where seat_id = ?", Integer.class, seat.getId()))
                .isEqualTo(3);
    }

    @Test
    void bookingEventMustMatchTheSeatsEvent() {
        Event other = saveEvent();

        assertThatThrownBy(() -> jdbc.update(
                "insert into bookings (user_id, event_id, seat_id, status) values (?, ?, ?, 'CONFIRMED')",
                user.getId(), other.getId(), seat.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_bookings_seat_event");
    }

    @Test
    void bookingRequiresExistingUser() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into bookings (user_id, event_id, seat_id, status) values (?, ?, ?, 'CONFIRMED')",
                9999L, event.getId(), seat.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancellationTimestampMustMatchStatus() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into bookings (user_id, event_id, seat_id, status) values (?, ?, ?, 'CANCELLED')",
                user.getId(), event.getId(), seat.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusColumnsRejectUnknownValues() {
        assertThatThrownBy(() -> jdbc.update("update seats set status = 'RESERVED' where id = ?", seat.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update events set status = 'DRAFT' where id = ?", event.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update users set role = 'ROOT' where id = ?", user.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatNumbersAreUniquePerEventOnly() {
        assertThatThrownBy(() -> saveSeat(event, "A1")).isInstanceOf(DataIntegrityViolationException.class);

        saveSeat(saveEvent(), "A1");
    }

    @Test
    void emailsAreUniqueAndLowercase() {
        assertThatThrownBy(() -> saveUser("user@example.com", Role.USER))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> saveUser("Mixed@Example.com", Role.USER))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyIsUniquePerUser() {
        jdbc.update("insert into idempotency_records (user_id, idempotency_key) values (?, 'k')", user.getId());

        assertThatThrownBy(() -> jdbc.update(
                "insert into idempotency_records (user_id, idempotency_key) values (?, 'k')", user.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingEventRemovesItsSeatsUnlessBookingsReferenceThem() {
        Event empty = saveEvent();
        saveSeat(empty, "B1");
        jdbc.update("delete from events where id = ?", empty.getId());
        assertThat(jdbc.queryForObject("select count(*) from seats where event_id = ?", Integer.class, empty.getId()))
                .isZero();

        insertBooking("CONFIRMED");
        assertThatThrownBy(() -> jdbc.update("delete from events where id = ?", event.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatRowLockBlocksOtherWritersUntilTheTransactionEnds() throws SQLException {
        AtomicReference<String> contenderSqlState = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            seatRepository.findByIdForUpdate(seat.getId()).orElseThrow();
            contenderSqlState.set(tryLockWithoutWaiting());
        });

        assertThat(contenderSqlState.get()).isEqualTo(LOCK_NOT_AVAILABLE);
        assertThat(tryLockWithoutWaiting()).isNull();
    }

    /** Uses a fresh connection, so it is a different session from the transaction holding the lock. */
    private String tryLockWithoutWaiting() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("select id from seats where id = " + seat.getId() + " for update nowait");
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private void insertBooking(String status) {
        jdbc.update("""
                insert into bookings (user_id, event_id, seat_id, status, cancelled_at)
                values (?, ?, ?, ?, case when ? = 'CANCELLED' then now() end)
                """, user.getId(), event.getId(), seat.getId(), status, status);
    }
}
