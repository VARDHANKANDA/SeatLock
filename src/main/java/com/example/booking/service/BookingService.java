package com.example.booking.service;

import com.example.booking.config.AppMetrics;
import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.dto.PageResponse;
import com.example.booking.entity.Booking;
import com.example.booking.entity.OutboxEventType;
import com.example.booking.entity.Seat;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.outbox.OutboxRecorder;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.IdempotencyRecordRepository;
import com.example.booking.repository.SeatRepository;
import com.example.booking.security.AuthenticatedUser;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking use cases.
 *
 * <p>Seat reservation is serialised per seat by a row lock ({@code SELECT ... FOR NO KEY UPDATE}) taken inside
 * the booking transaction, with a partial unique index on {@code bookings(seat_id) WHERE status = 'CONFIRMED'}
 * as a second line of defence. The event row is locked {@code FOR SHARE} so that an event update (for example
 * cancelling it) cannot interleave with a booking that already validated the old status.
 *
 * <p>Lock order is always: idempotency key, event, seat. Event updates and deletes lock the event first and
 * its seats afterwards (cascade). Cancellation takes the booking row, then the seat, and never an event. No
 * code path locks a seat and then an event or an existing booking, so these orders cannot form a cycle.
 *
 * <p>Every state change records its outbox event in the same transaction.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final BookingPreconditions preconditions;
    private final BookingWriter bookingWriter;
    private final LockTimeouts lockTimeouts;
    private final ReservationHoldService holds;
    private final OutboxRecorder outbox;
    private final AppMetrics metrics;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, SeatRepository seatRepository,
                          IdempotencyRecordRepository idempotencyRepository, BookingPreconditions preconditions,
                          BookingWriter bookingWriter, LockTimeouts lockTimeouts, ReservationHoldService holds,
                          OutboxRecorder outbox, AppMetrics metrics, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.preconditions = preconditions;
        this.bookingWriter = bookingWriter;
        this.lockTimeouts = lockTimeouts;
        this.holds = holds;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public BookingResult createBooking(Long userId, CreateBookingRequest request, String idempotencyKey) {
        lockTimeouts.apply();
        log.info("booking.attempt userId={} eventId={} seatId={}", userId, request.eventId(), request.seatId());

        if (idempotencyKey != null && idempotencyRepository.claim(userId, idempotencyKey) == 0) {
            return replay(userId, idempotencyKey, request);
        }

        preconditions.requireBookable(request.eventId());
        Seat seat = preconditions.lockSeat(request.eventId(), request.seatId());
        holds.releaseIfLapsed(seat);
        if (!seat.isAvailable()) {
            throw seatTaken(userId, seat);
        }

        Booking booking = bookingWriter.confirm(userId, seat);
        if (idempotencyKey != null) {
            idempotencyRepository.attachBooking(userId, idempotencyKey, booking.getId());
        }
        return new BookingResult(BookingResponse.from(booking), false);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Long bookingId, AuthenticatedUser actor) {
        Booking booking = bookingRepository.findWithSeatById(bookingId).orElseThrow(BookingService::notFound);
        requireAccess(booking, actor);
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listForUser(Long userId, int page, int size) {
        return PageResponse.from(
                bookingRepository.findByUserId(userId, PageRequest.of(page, size, NEWEST_FIRST)),
                BookingResponse::from);
    }

    @Transactional
    public BookingResponse cancel(Long bookingId, AuthenticatedUser actor) {
        lockTimeouts.apply();
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(BookingService::notFound);
        requireAccess(booking, actor);
        if (!booking.isConfirmed()) {
            throw ApiException.conflict(ErrorCode.BOOKING_ALREADY_CANCELLED, "The booking is already cancelled.");
        }
        Seat seat = seatRepository.findByIdForUpdate(booking.getSeat().getId()).orElseThrow();
        booking.cancel(clock.instant());
        seat.markAvailable();
        outbox.recordBooking(OutboxEventType.BOOKING_CANCELLED, booking);
        log.info("booking.cancelled bookingId={} actorId={} seatId={}", bookingId, actor.id(), seat.getId());
        return BookingResponse.from(booking);
    }

    private BookingResult replay(Long userId, String idempotencyKey, CreateBookingRequest request) {
        Long bookingId = idempotencyRepository.findBookingId(userId, idempotencyKey).orElseThrow();
        Booking booking = bookingRepository.findWithSeatById(bookingId).orElseThrow();
        if (!booking.getSeat().getId().equals(request.seatId()) || !booking.getEventId().equals(request.eventId())) {
            throw ApiException.unprocessable(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The Idempotency-Key was already used for a different request.");
        }
        log.info("booking.replayed bookingId={} userId={}", bookingId, userId);
        return new BookingResult(BookingResponse.from(booking), true);
    }

    private ApiException seatTaken(Long userId, Seat seat) {
        metrics.bookingConflict();
        log.info("booking.conflict userId={} eventId={} seatId={}", userId, seat.getEventId(), seat.getId());
        return ApiException.conflict(ErrorCode.SEAT_ALREADY_BOOKED, "The requested seat is no longer available.");
    }

    private static void requireAccess(Booking booking, AuthenticatedUser actor) {
        if (!actor.isAdmin() && !booking.getUserId().equals(actor.id())) {
            throw ApiException.forbidden("You do not have access to this booking.");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.BOOKING_NOT_FOUND, "Booking not found.");
    }
}
