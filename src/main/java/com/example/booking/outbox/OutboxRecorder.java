package com.example.booking.outbox;

import com.example.booking.entity.AggregateType;
import com.example.booking.entity.Booking;
import com.example.booking.entity.OutboxEvent;
import com.example.booking.entity.OutboxEventType;
import com.example.booking.entity.ReservationHold;
import com.example.booking.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes outbox rows. Callers must already be in a transaction: the event commits or rolls back together
 * with the business change it describes, which is the point of the pattern. Payloads carry business
 * identifiers only, never credentials or tokens.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBooking(OutboxEventType type, Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("userId", booking.getUserId());
        payload.put("eventId", booking.getEventId());
        payload.put("seatId", booking.getSeat().getId());
        payload.put("seatNumber", booking.getSeat().getSeatNumber());
        payload.put("status", booking.getStatus());
        save(AggregateType.BOOKING, booking.getId(), type, payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordHold(OutboxEventType type, ReservationHold hold) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdId", hold.getId());
        payload.put("userId", hold.getUserId());
        payload.put("eventId", hold.getEventId());
        payload.put("seatId", hold.getSeat().getId());
        payload.put("seatNumber", hold.getSeat().getSeatNumber());
        payload.put("status", hold.getStatus());
        payload.put("expiresAt", hold.getExpiresAt());
        save(AggregateType.RESERVATION, hold.getId(), type, payload);
    }

    private void save(AggregateType aggregateType, Long aggregateId, OutboxEventType type, Map<String, Object> payload) {
        try {
            repository.save(new OutboxEvent(aggregateType, aggregateId, type,
                    objectMapper.writeValueAsString(payload), clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload could not be serialised", e);
        }
    }
}
