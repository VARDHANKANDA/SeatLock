package com.example.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.booking.config.AppMetrics;
import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.Event;
import com.example.booking.entity.OutboxEventType;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.SeatStatus;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.outbox.OutboxRecorder;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.IdempotencyRecordRepository;
import com.example.booking.repository.SeatRepository;
import com.example.booking.security.AuthenticatedUser;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final long USER_ID = 7L;
    private static final long EVENT_ID = 10L;
    private static final long SEAT_ID = 42L;

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private IdempotencyRecordRepository idempotencyRepository;
    @Mock
    private OutboxRecorder outbox;
    @Mock
    private ReservationHoldService holds;
    @Mock
    private LockTimeouts lockTimeouts;

    private BookingService service;
    private Seat seat;
    private final CreateBookingRequest request = new CreateBookingRequest(EVENT_ID, SEAT_ID);

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
        service = new BookingService(bookingRepository, seatRepository, idempotencyRepository,
                new BookingPreconditions(eventRepository, seatRepository, clock),
                new BookingWriter(bookingRepository, outbox, metrics), lockTimeouts, holds, outbox, metrics, clock);
        seat = seat(SEAT_ID, EVENT_ID);
        when(seatRepository.findByIdForUpdate(SEAT_ID)).thenReturn(Optional.of(seat));
        when(eventRepository.findByIdForShare(EVENT_ID))
                .thenReturn(Optional.of(event(EventStatus.SCHEDULED, NOW.plus(Duration.ofDays(1)))));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            ReflectionTestUtils.setField(booking, "id", 100L);
            return booking;
        });
    }

    @Test
    void booksAvailableSeatAndMarksItBooked() {
        BookingResult result = service.createBooking(USER_ID, request, null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.booking().id()).isEqualTo(100L);
        assertThat(result.booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.booking().seatNumber()).isEqualTo("A1");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        verifyNoInteractions(idempotencyRepository);
        verify(holds).releaseIfLapsed(seat);
        verify(outbox).recordBooking(eq(OutboxEventType.BOOKING_CONFIRMED), any(Booking.class));
    }

    @Test
    void bookingAppliesTheLockTimeoutBeforeTakingAnyLock() {
        when(idempotencyRepository.claim(USER_ID, "key-1")).thenReturn(1);

        service.createBooking(USER_ID, request, "key-1");

        InOrder order = inOrder(lockTimeouts, idempotencyRepository, seatRepository);
        order.verify(lockTimeouts).apply();
        order.verify(idempotencyRepository).claim(USER_ID, "key-1");
        order.verify(seatRepository).findByIdForUpdate(SEAT_ID);
    }

    @Test
    void cancellationAppliesTheLockTimeoutBeforeLockingTheBooking() {
        seat.markBooked();
        when(bookingRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(booking(55L, USER_ID, seat)));

        service.cancel(55L, new AuthenticatedUser(USER_ID, Role.USER));

        InOrder order = inOrder(lockTimeouts, bookingRepository);
        order.verify(lockTimeouts).apply();
        order.verify(bookingRepository).findByIdForUpdate(55L);
    }

    @Test
    void rejectsSeatThatIsAlreadyBooked() {
        seat.markBooked();

        assertApiError(() -> service.createBooking(USER_ID, request, null), ErrorCode.SEAT_ALREADY_BOOKED);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnknownSeat() {
        when(seatRepository.findByIdForUpdate(SEAT_ID)).thenReturn(Optional.empty());

        assertApiError(() -> service.createBooking(USER_ID, request, null), ErrorCode.SEAT_NOT_FOUND);
    }

    @Test
    void rejectsSeatBelongingToAnotherEvent() {
        when(seatRepository.findByIdForUpdate(SEAT_ID)).thenReturn(Optional.of(seat(SEAT_ID, 99L)));

        assertApiError(() -> service.createBooking(USER_ID, request, null), ErrorCode.SEAT_NOT_FOUND);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCancelledEvent() {
        when(eventRepository.findByIdForShare(EVENT_ID))
                .thenReturn(Optional.of(event(EventStatus.CANCELLED, NOW.plus(Duration.ofDays(1)))));

        assertApiError(() -> service.createBooking(USER_ID, request, null), ErrorCode.EVENT_NOT_BOOKABLE);
        assertThat(seat.isAvailable()).isTrue();
    }

    @Test
    void rejectsEventThatAlreadyStarted() {
        when(eventRepository.findByIdForShare(EVENT_ID))
                .thenReturn(Optional.of(event(EventStatus.SCHEDULED, NOW.minus(Duration.ofMinutes(1)))));

        assertApiError(() -> service.createBooking(USER_ID, request, null), ErrorCode.EVENT_NOT_BOOKABLE);
    }

    @Test
    void translatesConfirmedSeatIndexViolationIntoConflict() {
        ConstraintViolationException violation =
                new ConstraintViolationException("duplicate", new SQLException(), BookingWriter.CONFIRMED_SEAT_CONSTRAINT);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenThrow(new DataIntegrityViolationException("x", violation));

        assertApiError(() -> service.createBooking(USER_ID, request, null), ErrorCode.SEAT_ALREADY_BOOKED);
    }

    @Test
    void rethrowsUnrelatedIntegrityViolations() {
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("other constraint"));

        assertThatThrownBy(() -> service.createBooking(USER_ID, request, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordsIdempotencyKeyAgainstTheNewBooking() {
        when(idempotencyRepository.claim(USER_ID, "key-1")).thenReturn(1);

        BookingResult result = service.createBooking(USER_ID, request, "key-1");

        assertThat(result.replayed()).isFalse();
        verify(idempotencyRepository).attachBooking(USER_ID, "key-1", 100L);
    }

    @Test
    void replaysOriginalBookingForRepeatedIdempotencyKey() {
        Booking existing = booking(55L, USER_ID, seat);
        when(idempotencyRepository.claim(USER_ID, "key-1")).thenReturn(0);
        when(idempotencyRepository.findBookingId(USER_ID, "key-1")).thenReturn(Optional.of(55L));
        when(bookingRepository.findWithSeatById(55L)).thenReturn(Optional.of(existing));

        BookingResult result = service.createBooking(USER_ID, request, "key-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking().id()).isEqualTo(55L);
        verify(seatRepository, never()).findByIdForUpdate(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsIdempotencyKeyReusedForDifferentSeat() {
        Booking existing = booking(55L, USER_ID, seat(SEAT_ID + 1, EVENT_ID));
        when(idempotencyRepository.claim(USER_ID, "key-1")).thenReturn(0);
        when(idempotencyRepository.findBookingId(USER_ID, "key-1")).thenReturn(Optional.of(55L));
        when(bookingRepository.findWithSeatById(55L)).thenReturn(Optional.of(existing));

        assertApiError(() -> service.createBooking(USER_ID, request, "key-1"), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void ownerCancelsBookingAndSeatBecomesAvailable() {
        seat.markBooked();
        Booking booking = booking(55L, USER_ID, seat);
        when(bookingRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(booking));

        BookingResponse response = service.cancel(55L, new AuthenticatedUser(USER_ID, Role.USER));

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.cancelledAt()).isEqualTo(NOW);
        assertThat(seat.isAvailable()).isTrue();
        verify(outbox).recordBooking(OutboxEventType.BOOKING_CANCELLED, booking);
    }

    @Test
    void adminCanCancelAnyonesBooking() {
        seat.markBooked();
        when(bookingRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(booking(55L, USER_ID, seat)));

        BookingResponse response = service.cancel(55L, new AuthenticatedUser(1L, Role.ADMIN));

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void otherUserCannotCancelBooking() {
        seat.markBooked();
        when(bookingRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(booking(55L, USER_ID, seat)));

        assertApiError(() -> service.cancel(55L, new AuthenticatedUser(8L, Role.USER)), ErrorCode.FORBIDDEN);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void cancellingTwiceIsAConflict() {
        Booking booking = booking(55L, USER_ID, seat);
        booking.cancel(NOW);
        when(bookingRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(booking));

        assertApiError(() -> service.cancel(55L, new AuthenticatedUser(USER_ID, Role.USER)),
                ErrorCode.BOOKING_ALREADY_CANCELLED);
    }

    @Test
    void cancellingUnknownBookingIsNotFound() {
        when(bookingRepository.findByIdForUpdate(55L)).thenReturn(Optional.empty());

        assertApiError(() -> service.cancel(55L, new AuthenticatedUser(USER_ID, Role.USER)), ErrorCode.BOOKING_NOT_FOUND);
    }

    @Test
    void userCannotReadAnotherUsersBooking() {
        when(bookingRepository.findWithSeatById(55L)).thenReturn(Optional.of(booking(55L, USER_ID, seat)));

        assertApiError(() -> service.get(55L, new AuthenticatedUser(8L, Role.USER)), ErrorCode.FORBIDDEN);
    }

    @Test
    void adminCanReadAnyBooking() {
        when(bookingRepository.findWithSeatById(55L)).thenReturn(Optional.of(booking(55L, USER_ID, seat)));

        assertThat(service.get(55L, new AuthenticatedUser(1L, Role.ADMIN)).id()).isEqualTo(55L);
    }

    private static void assertApiError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }

    private static Seat seat(long id, long eventId) {
        Seat seat = new Seat(eventId, "A1");
        ReflectionTestUtils.setField(seat, "id", id);
        return seat;
    }

    private static Event event(EventStatus status, Instant when) {
        return new Event("Concert", null, "Hall", when, status);
    }

    private static Booking booking(long id, long userId, Seat seat) {
        Booking booking = new Booking(userId, seat);
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }
}
