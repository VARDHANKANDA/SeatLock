package com.example.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.booking.config.AppMetrics;
import com.example.booking.config.ReservationProperties;
import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateHoldRequest;
import com.example.booking.dto.HoldResponse;
import com.example.booking.entity.Booking;
import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.HoldStatus;
import com.example.booking.entity.OutboxEventType;
import com.example.booking.entity.ReservationHold;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.SeatStatus;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.outbox.OutboxRecorder;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.ReservationHoldRepository;
import com.example.booking.repository.ReservationHoldRepository.HoldRef;
import com.example.booking.repository.SeatRepository;
import com.example.booking.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationHoldServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final long USER_ID = 7L;
    private static final long EVENT_ID = 10L;
    private static final long SEAT_ID = 42L;
    private static final long HOLD_ID = 55L;

    @Mock
    private ReservationHoldRepository holdRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private OutboxRecorder outbox;
    @Mock
    private LockTimeouts lockTimeouts;

    private ReservationHoldService service;
    private Seat seat;
    private final AuthenticatedUser owner = new AuthenticatedUser(USER_ID, Role.USER);

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
        service = new ReservationHoldService(holdRepository, seatRepository,
                new BookingPreconditions(eventRepository, seatRepository, clock),
                new BookingWriter(bookingRepository, outbox, metrics), lockTimeouts, outbox,
                new ReservationProperties(300, 30, 100), metrics, clock);
        seat = seat();
        when(seatRepository.findByIdForUpdate(SEAT_ID)).thenReturn(Optional.of(seat));
        when(eventRepository.findByIdForShare(EVENT_ID)).thenReturn(Optional.of(bookableEvent()));
        when(holdRepository.saveAndFlush(any(ReservationHold.class))).thenAnswer(invocation -> {
            ReservationHold hold = invocation.getArgument(0);
            ReflectionTestUtils.setField(hold, "id", HOLD_ID);
            return hold;
        });
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            ReflectionTestUtils.setField(booking, "id", 100L);
            return booking;
        });
        HoldRef ownerRef = ref(USER_ID);
        when(holdRepository.findRefById(HOLD_ID)).thenReturn(Optional.of(ownerRef));
    }

    @Test
    void holdsAnAvailableSeatForTheConfiguredDuration() {
        HoldResponse response = service.createHold(USER_ID, new CreateHoldRequest(EVENT_ID, SEAT_ID));

        assertThat(response.status()).isEqualTo(HoldStatus.HELD);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofSeconds(300)));
        assertThat(response.seatNumber()).isEqualTo("A1");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(outbox).recordHold(eq(OutboxEventType.RESERVATION_HELD), any(ReservationHold.class));
    }

    @Test
    void creatingAHoldAppliesTheLockTimeoutBeforeLockingTheSeat() {
        service.createHold(USER_ID, new CreateHoldRequest(EVENT_ID, SEAT_ID));

        assertTimeoutAppliedBeforeSeatLock();
    }

    @Test
    void confirmingAHoldAppliesTheLockTimeoutBeforeLockingTheSeat() {
        seat.markHeld();
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(hold(NOW.plusSeconds(60))));

        service.confirm(HOLD_ID, owner);

        assertTimeoutAppliedBeforeSeatLock();
    }

    @Test
    void cancellingAHoldAppliesTheLockTimeoutBeforeLockingTheSeat() {
        seat.markHeld();
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(hold(NOW.plusSeconds(60))));

        service.cancel(HOLD_ID, owner);

        assertTimeoutAppliedBeforeSeatLock();
    }

    @Test
    void expiringAHoldAppliesTheLockTimeoutBeforeLockingTheSeat() {
        seat.markHeld();
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(hold(NOW.minusSeconds(1))));

        service.expire(HOLD_ID);

        assertTimeoutAppliedBeforeSeatLock();
    }

    @Test
    void rejectsSeatThatIsHeldOrBooked() {
        seat.markHeld();
        ReservationHold active = hold(NOW.plusSeconds(60));
        when(holdRepository.findActiveBySeatId(SEAT_ID)).thenReturn(Optional.of(active));

        assertApiError(() -> service.createHold(USER_ID, new CreateHoldRequest(EVENT_ID, SEAT_ID)),
                ErrorCode.SEAT_ALREADY_BOOKED);
        seat.markBooked();
        assertApiError(() -> service.createHold(USER_ID, new CreateHoldRequest(EVENT_ID, SEAT_ID)),
                ErrorCode.SEAT_ALREADY_BOOKED);
        verify(holdRepository, never()).saveAndFlush(any());
    }

    @Test
    void releasesALapsedHoldBeforeHoldingTheSeatAgain() {
        seat.markHeld();
        ReservationHold lapsed = hold(NOW.minusSeconds(1));
        when(holdRepository.findActiveBySeatId(SEAT_ID)).thenReturn(Optional.of(lapsed));

        HoldResponse response = service.createHold(USER_ID + 1, new CreateHoldRequest(EVENT_ID, SEAT_ID));

        assertThat(lapsed.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(response.status()).isEqualTo(HoldStatus.HELD);
        verify(outbox).recordHold(OutboxEventType.RESERVATION_EXPIRED, lapsed);
    }

    @Test
    void rejectsEventThatIsNotOpen() {
        when(eventRepository.findByIdForShare(EVENT_ID)).thenReturn(Optional.of(
                new Event("Concert", null, "Hall", NOW.plus(Duration.ofDays(1)), EventStatus.CANCELLED)));

        assertApiError(() -> service.createHold(USER_ID, new CreateHoldRequest(EVENT_ID, SEAT_ID)),
                ErrorCode.EVENT_NOT_BOOKABLE);
    }

    @Test
    void ownerConfirmsActiveHoldIntoABooking() {
        seat.markHeld();
        ReservationHold active = hold(NOW.plusSeconds(60));
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(active));

        BookingResponse booking = service.confirm(HOLD_ID, owner);

        assertThat(booking.id()).isEqualTo(100L);
        assertThat(active.getStatus()).isEqualTo(HoldStatus.CONVERTED);
        assertThat(active.getBookingId()).isEqualTo(100L);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        verify(outbox).recordBooking(eq(OutboxEventType.BOOKING_CONFIRMED), any(Booking.class));
    }

    @Test
    void confirmationRequiresTheOwnerEvenForAdmins() {
        assertApiError(() -> service.confirm(HOLD_ID, new AuthenticatedUser(8L, Role.USER)), ErrorCode.FORBIDDEN);
        assertApiError(() -> service.confirm(HOLD_ID, new AuthenticatedUser(1L, Role.ADMIN)), ErrorCode.FORBIDDEN);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void lapsedHoldCannotBeConfirmed() {
        seat.markHeld();
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(hold(NOW.minusSeconds(1))));

        assertApiError(() -> service.confirm(HOLD_ID, owner), ErrorCode.HOLD_EXPIRED);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void holdThatIsNoLongerActiveCannotBeConfirmedOrCancelled() {
        ReservationHold finished = hold(NOW.plusSeconds(60));
        finished.cancel();
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(finished));

        assertApiError(() -> service.confirm(HOLD_ID, owner), ErrorCode.HOLD_NOT_ACTIVE);
        assertApiError(() -> service.cancel(HOLD_ID, owner), ErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    void unknownHoldIsNotFound() {
        when(holdRepository.findRefById(HOLD_ID)).thenReturn(Optional.empty());

        assertApiError(() -> service.confirm(HOLD_ID, owner), ErrorCode.HOLD_NOT_FOUND);
        assertApiError(() -> service.cancel(HOLD_ID, owner), ErrorCode.HOLD_NOT_FOUND);
    }

    @Test
    void cancellingReleasesTheSeatAndKeepsTheHoldAsHistory() {
        seat.markHeld();
        ReservationHold active = hold(NOW.plusSeconds(60));
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(active));

        HoldResponse response = service.cancel(HOLD_ID, owner);

        assertThat(response.status()).isEqualTo(HoldStatus.CANCELLED);
        assertThat(seat.isAvailable()).isTrue();
        verify(outbox).recordHold(OutboxEventType.RESERVATION_CANCELLED, active);
    }

    @Test
    void adminMayCancelButOtherUsersMayNot() {
        seat.markHeld();
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(hold(NOW.plusSeconds(60))));

        assertApiError(() -> service.cancel(HOLD_ID, new AuthenticatedUser(8L, Role.USER)), ErrorCode.FORBIDDEN);
        assertThat(service.cancel(HOLD_ID, new AuthenticatedUser(1L, Role.ADMIN)).status())
                .isEqualTo(HoldStatus.CANCELLED);
    }

    @Test
    void expireReleasesALapsedHoldAndReportsIt() {
        seat.markHeld();
        ReservationHold lapsed = hold(NOW.minusSeconds(1));
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(lapsed));

        assertThat(service.expire(HOLD_ID)).isTrue();

        assertThat(lapsed.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seat.isAvailable()).isTrue();
        verify(outbox).recordHold(OutboxEventType.RESERVATION_EXPIRED, lapsed);
    }

    @Test
    void expireSkipsHoldsThatAreNotDueOrNotActive() {
        seat.markHeld();
        ReservationHold future = hold(NOW.plusSeconds(60));
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(future));
        assertThat(service.expire(HOLD_ID)).isFalse();
        assertThat(future.isActive()).isTrue();

        ReservationHold converted = hold(NOW.minusSeconds(1));
        converted.convertTo(100L);
        when(holdRepository.findWithSeatById(HOLD_ID)).thenReturn(Optional.of(converted));
        assertThat(service.expire(HOLD_ID)).isFalse();

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(outbox, never()).recordHold(eq(OutboxEventType.RESERVATION_EXPIRED), any());
    }

    private void assertTimeoutAppliedBeforeSeatLock() {
        InOrder order = inOrder(lockTimeouts, seatRepository);
        order.verify(lockTimeouts).apply();
        order.verify(seatRepository).findByIdForUpdate(SEAT_ID);
    }

    private static void assertApiError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    private HoldRef ref(long userId) {
        HoldRef ref = mock(HoldRef.class);
        when(ref.getUserId()).thenReturn(userId);
        when(ref.getEventId()).thenReturn(EVENT_ID);
        when(ref.getSeatId()).thenReturn(SEAT_ID);
        return ref;
    }

    private ReservationHold hold(Instant expiresAt) {
        ReservationHold hold = new ReservationHold(USER_ID, seat, expiresAt);
        ReflectionTestUtils.setField(hold, "id", HOLD_ID);
        return hold;
    }

    private static Seat seat() {
        Seat seat = new Seat(EVENT_ID, "A1");
        ReflectionTestUtils.setField(seat, "id", SEAT_ID);
        return seat;
    }

    private static Event bookableEvent() {
        return new Event("Concert", null, "Hall", NOW.plus(Duration.ofDays(1)), EventStatus.SCHEDULED);
    }
}
