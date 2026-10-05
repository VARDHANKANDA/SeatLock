package com.example.booking.service;

import com.example.booking.entity.Event;
import com.example.booking.entity.Seat;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.SeatRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lock-taking checks shared by bookings and holds. They must run inside the caller's transaction and in this
 * order: event ({@code FOR SHARE}), then seat ({@code FOR NO KEY UPDATE}). Both reads hit PostgreSQL directly.
 */
@Component
public class BookingPreconditions {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public BookingPreconditions(EventRepository eventRepository, SeatRepository seatRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
    }

    /** Locks the event row in shared mode so an event update cannot interleave with this transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireBookable(Long eventId) {
        Event event = eventRepository.findByIdForShare(eventId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.EVENT_NOT_FOUND, "Event not found."));
        if (!event.isBookableAt(clock.instant())) {
            throw ApiException.conflict(ErrorCode.EVENT_NOT_BOOKABLE, "The event is not open for booking.");
        }
    }

    /** Locks the seat row and checks that it belongs to the event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Seat lockSeat(Long eventId, Long seatId) {
        return seatRepository.findByIdForUpdate(seatId)
                .filter(locked -> locked.getEventId().equals(eventId))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.SEAT_NOT_FOUND, "Seat not found for the given event."));
    }
}
