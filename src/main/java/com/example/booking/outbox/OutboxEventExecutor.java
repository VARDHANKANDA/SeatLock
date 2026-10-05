package com.example.booking.outbox;

import com.example.booking.config.OutboxProperties;
import com.example.booking.entity.OutboxEvent;
import com.example.booking.repository.OutboxEventRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps of outbox processing, one short transaction per event. They live in their own
 * bean so the calls from {@link OutboxProcessor} go through the transaction proxy.
 */
@Component
class OutboxEventExecutor {

    private final OutboxEventRepository repository;
    private final NotificationService notifications;
    private final OutboxProperties properties;
    private final Clock clock;

    OutboxEventExecutor(OutboxEventRepository repository, NotificationService notifications,
                        OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Locks the event (skipping rows another worker holds), runs the handler and marks it processed.
     * A handler failure propagates and rolls everything back, leaving the event pending.
     */
    @Transactional
    boolean deliver(Long eventId) {
        OutboxEvent event = repository.lockPendingById(eventId).orElse(null);
        if (event == null) {
            return false;
        }
        notifications.handle(event);
        event.markProcessed(clock.instant());
        return true;
    }

    @Transactional
    void recordFailure(Long eventId, String error) {
        repository.lockPendingById(eventId)
                .ifPresent(event -> event.recordFailure(error, clock.instant(), properties.maxAttempts()));
    }
}
