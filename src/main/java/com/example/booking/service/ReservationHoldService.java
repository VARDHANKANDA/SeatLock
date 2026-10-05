package com.example.booking.service;

import com.example.booking.config.AppMetrics;
import com.example.booking.config.ReservationProperties;
import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateHoldRequest;
import com.example.booking.dto.HoldResponse;
import com.example.booking.entity.Booking;
import com.example.booking.entity.OutboxEventType;
import com.example.booking.entity.ReservationHold;
import com.example.booking.entity.Seat;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.outbox.OutboxRecorder;
import com.example.booking.repository.ReservationHoldRepository;
import com.example.booking.repository.ReservationHoldRepository.HoldRef;
import com.example.booking.repository.SeatRepository;
import com.example.booking.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporary seat reservations: AVAILABLE -> HELD -> BOOKED, or HELD -> AVAILABLE when the hold is cancelled
 * or lapses.
 *
 * <p>Every hold transition (create, confirm, cancel, expire) happens while holding the seat's row lock, taken
 * with the same lock order as bookings (event share lock, then seat). Because the seat lock serialises all
 * transitions of one seat, a hold can never expire while it is being confirmed, and a seat has at most one
 * active hold; a partial unique index enforces the latter in the database as well. A hold entity is always
 * loaded after the seat lock is taken so that its state is current.
 *
 * <p>Expiry is lazy as well as scheduled: a seat whose hold has lapsed is released by whichever transaction
 * touches it next, so correctness never depends on the sweeper's timing.
 */
@Service
public class ReservationHoldService {

    static final String ACTIVE_HOLD_CONSTRAINT = "uq_holds_active_seat";

    private static final Logger log = LoggerFactory.getLogger(ReservationHoldService.class);

    private final ReservationHoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final BookingPreconditions preconditions;
    private final BookingWriter bookingWriter;
    private final LockTimeouts lockTimeouts;
    private final OutboxRecorder outbox;
    private final ReservationProperties properties;
    private final AppMetrics metrics;
    private final Clock clock;

    public ReservationHoldService(ReservationHoldRepository holdRepository, SeatRepository seatRepository,
                                  BookingPreconditions preconditions, BookingWriter bookingWriter, LockTimeouts lockTimeouts,
                                  OutboxRecorder outbox, ReservationProperties properties, AppMetrics metrics,
                                  Clock clock) {
        this.holdRepository = holdRepository;
        this.seatRepository = seatRepository;
        this.preconditions = preconditions;
        this.bookingWriter = bookingWriter;
        this.lockTimeouts = lockTimeouts;
        this.outbox = outbox;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public HoldResponse createHold(Long userId, CreateHoldRequest request) {
        lockTimeouts.apply();
        log.info("hold.attempt userId={} eventId={} seatId={}", userId, request.eventId(), request.seatId());
        preconditions.requireBookable(request.eventId());
        Seat seat = preconditions.lockSeat(request.eventId(), request.seatId());
        releaseIfLapsed(seat);
        if (!seat.isAvailable()) {
            throw holdConflict(userId, seat);
        }

        seat.markHeld();
        ReservationHold hold = persist(userId, seat);
        outbox.recordHold(OutboxEventType.RESERVATION_HELD, hold);
        metrics.holdSuccess();
        log.info("hold.created holdId={} userId={} seatId={} expiresAt={}",
                hold.getId(), userId, seat.getId(), hold.getExpiresAt());
        return HoldResponse.from(hold);
    }

    @Transactional
    public BookingResponse confirm(Long holdId, AuthenticatedUser actor) {
        lockTimeouts.apply();
        HoldRef ref = holdRepository.findRefById(holdId).orElseThrow(ReservationHoldService::notFound);
        if (!ref.getUserId().equals(actor.id())) {
            throw ApiException.forbidden("You do not have access to this hold.");
        }
        preconditions.requireBookable(ref.getEventId());
        Seat seat = preconditions.lockSeat(ref.getEventId(), ref.getSeatId());

        ReservationHold hold = holdRepository.findWithSeatById(holdId).orElseThrow(ReservationHoldService::notFound);
        requireActive(hold);
        if (hold.isLapsedAt(clock.instant())) {
            throw ApiException.conflict(ErrorCode.HOLD_EXPIRED, "The hold has expired.");
        }

        Booking booking = bookingWriter.confirm(actor.id(), seat);
        hold.convertTo(booking.getId());
        log.info("hold.converted holdId={} bookingId={} userId={}", holdId, booking.getId(), actor.id());
        return BookingResponse.from(booking);
    }

    @Transactional
    public HoldResponse cancel(Long holdId, AuthenticatedUser actor) {
        lockTimeouts.apply();
        HoldRef ref = holdRepository.findRefById(holdId).orElseThrow(ReservationHoldService::notFound);
        if (!actor.isAdmin() && !ref.getUserId().equals(actor.id())) {
            throw ApiException.forbidden("You do not have access to this hold.");
        }
        Seat seat = seatRepository.findByIdForUpdate(ref.getSeatId()).orElseThrow();

        ReservationHold hold = holdRepository.findWithSeatById(holdId).orElseThrow(ReservationHoldService::notFound);
        requireActive(hold);
        hold.cancel();
        seat.markAvailable();
        outbox.recordHold(OutboxEventType.RESERVATION_CANCELLED, hold);
        log.info("hold.cancelled holdId={} actorId={} seatId={}", holdId, actor.id(), seat.getId());
        return HoldResponse.from(hold);
    }

    /** Expires one lapsed hold in its own transaction; returns false if it was no longer due. */
    @Transactional
    public boolean expire(Long holdId) {
        lockTimeouts.apply();
        HoldRef ref = holdRepository.findRefById(holdId).orElse(null);
        if (ref == null) {
            return false;
        }
        Seat seat = seatRepository.findByIdForUpdate(ref.getSeatId()).orElseThrow();
        ReservationHold hold = holdRepository.findWithSeatById(holdId).orElseThrow();
        if (!hold.isActive() || !hold.isLapsedAt(clock.instant())) {
            return false;
        }
        expireLocked(hold, seat);
        return true;
    }

    /**
     * Releases the seat if it is held by a lapsed hold. The seat must already be locked by the caller. The
     * expiry is flushed immediately: the caller may insert a new active hold next, and Hibernate would
     * otherwise run that insert before this update and trip the one-active-hold-per-seat index.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseIfLapsed(Seat seat) {
        if (seat.isHeld()) {
            holdRepository.findActiveBySeatId(seat.getId())
                    .filter(hold -> hold.isLapsedAt(clock.instant()))
                    .ifPresent(hold -> {
                        expireLocked(hold, seat);
                        holdRepository.flush();
                    });
        }
    }

    private void expireLocked(ReservationHold hold, Seat seat) {
        hold.expire();
        seat.markAvailable();
        outbox.recordHold(OutboxEventType.RESERVATION_EXPIRED, hold);
        metrics.holdExpired();
        log.info("hold.expired holdId={} userId={} seatId={}", hold.getId(), hold.getUserId(), seat.getId());
    }

    private ReservationHold persist(Long userId, Seat seat) {
        try {
            return holdRepository.saveAndFlush(
                    new ReservationHold(userId, seat, clock.instant().plus(Duration.ofSeconds(properties.holdDurationSeconds()))));
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && ACTIVE_HOLD_CONSTRAINT.equals(violation.getConstraintName())) {
                throw holdConflict(userId, seat);
            }
            throw e;
        }
    }

    private static void requireActive(ReservationHold hold) {
        if (!hold.isActive()) {
            throw ApiException.conflict(ErrorCode.HOLD_NOT_ACTIVE, "The hold is no longer active.");
        }
    }

    private ApiException holdConflict(Long userId, Seat seat) {
        metrics.holdConflict();
        log.info("hold.conflict userId={} eventId={} seatId={}", userId, seat.getEventId(), seat.getId());
        return ApiException.conflict(ErrorCode.SEAT_ALREADY_BOOKED, "The requested seat is no longer available.");
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.HOLD_NOT_FOUND, "Hold not found.");
    }
}
