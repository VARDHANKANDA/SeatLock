package com.example.booking.outbox;

import com.example.booking.entity.OutboxEvent;
import com.example.booking.repository.NotificationLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal notification sink: records one notification per outbox event and logs it. Outbox delivery is
 * at-least-once, so this handler is idempotent. The notification row is unique per outbox event id, and the
 * side effect (the log line) only happens for the delivery that actually inserted it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository repository;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(OutboxEvent event) {
        JsonNode payload = parse(event);
        long userId = payload.path("userId").asLong();
        String message = describe(event, payload);
        int inserted = repository.insertIfAbsent(event.getId(), userId, event.getEventType().name(), message);
        if (inserted == 1) {
            log.info("notification.sent outboxEventId={} type={} userId={} message=\"{}\"",
                    event.getId(), event.getEventType(), userId, message);
        } else {
            log.info("notification.duplicate outboxEventId={} type={}", event.getId(), event.getEventType());
        }
    }

    private JsonNode parse(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload " + event.getId() + " is not valid JSON", e);
        }
    }

    private String describe(OutboxEvent event, JsonNode payload) {
        String seat = payload.path("seatNumber").asText();
        long eventId = payload.path("eventId").asLong();
        return switch (event.getEventType()) {
            case BOOKING_CONFIRMED -> "Your booking for seat " + seat + " at event " + eventId + " is confirmed.";
            case BOOKING_CANCELLED -> "Your booking for seat " + seat + " at event " + eventId + " was cancelled.";
            case RESERVATION_HELD -> "Seat " + seat + " at event " + eventId + " is held for you until "
                    + payload.path("expiresAt").asText() + ".";
            case RESERVATION_EXPIRED -> "Your hold on seat " + seat + " at event " + eventId + " has expired.";
            case RESERVATION_CANCELLED -> "Your hold on seat " + seat + " at event " + eventId + " was cancelled.";
        };
    }
}
