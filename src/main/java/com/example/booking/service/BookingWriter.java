package com.example.booking.service;

import com.example.booking.config.AppMetrics;
import com.example.booking.entity.Booking;
import com.example.booking.entity.OutboxEventType;
import com.example.booking.entity.Seat;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.outbox.OutboxRecorder;
import com.example.booking.repository.BookingRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates the CONFIRMED booking for a locked seat, together with its outbox event. */
@Component
public class BookingWriter {

    static final String CONFIRMED_SEAT_CONSTRAINT = "uq_bookings_confirmed_seat";

    private static final Logger log = LoggerFactory.getLogger(BookingWriter.class);

    private final BookingRepository bookingRepository;
    private final OutboxRecorder outbox;
    private final AppMetrics metrics;

    public BookingWriter(BookingRepository bookingRepository, OutboxRecorder outbox, AppMetrics metrics) {
        this.bookingRepository = bookingRepository;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    /** The caller must hold the seat lock and have verified the seat can be booked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking confirm(Long userId, Seat seat) {
        seat.markBooked();
        Booking booking = persist(userId, seat);
        outbox.recordBooking(OutboxEventType.BOOKING_CONFIRMED, booking);
        metrics.bookingSuccess();
        log.info("booking.confirmed bookingId={} userId={} eventId={} seatId={}",
                booking.getId(), userId, booking.getEventId(), seat.getId());
        return booking;
    }

    private Booking persist(Long userId, Seat seat) {
        try {
            return bookingRepository.saveAndFlush(new Booking(userId, seat));
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && CONFIRMED_SEAT_CONSTRAINT.equals(violation.getConstraintName())) {
                metrics.bookingConflict();
                log.info("booking.conflict userId={} eventId={} seatId={}", userId, seat.getEventId(), seat.getId());
                throw ApiException.conflict(ErrorCode.SEAT_ALREADY_BOOKED, "The requested seat is no longer available.");
            }
            throw e;
        }
    }
}
