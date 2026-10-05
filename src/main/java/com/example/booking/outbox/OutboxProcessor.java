package com.example.booking.outbox;

import com.example.booking.config.AppMetrics;
import com.example.booking.config.OutboxProperties;
import com.example.booking.entity.OutboxStatus;
import com.example.booking.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Delivers pending outbox events with at-least-once semantics: an event is marked processed only after its
 * handler returned, so a crash in between causes a redelivery rather than a loss. Handlers must therefore be
 * idempotent. Each batch is bounded and each event is processed in its own short transaction; failures are
 * retried with exponential backoff until the configured number of attempts is used up.
 *
 * <p>Several instances may run this concurrently: {@code FOR UPDATE SKIP LOCKED} gives each event to one worker.
 */
@Service
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository repository;
    private final OutboxEventExecutor executor;
    private final OutboxProperties properties;
    private final AppMetrics metrics;
    private final Clock clock;

    public OutboxProcessor(OutboxEventRepository repository, OutboxEventExecutor executor,
                           OutboxProperties properties, AppMetrics metrics, MeterRegistry registry, Clock clock) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        Gauge.builder("outbox.pending", repository, r -> r.countByStatus(OutboxStatus.PENDING)).register(registry);
    }

    /** Processes up to one batch of due events and returns how many were delivered. */
    public int processBatch() {
        List<Long> dueIds = repository.findDueIds(clock.instant(), PageRequest.of(0, properties.batchSize()));
        int delivered = 0;
        for (Long id : dueIds) {
            try {
                if (executor.deliver(id)) {
                    metrics.outboxProcessed();
                    delivered++;
                }
            } catch (RuntimeException e) {
                recordFailure(id, e);
            }
        }
        return delivered;
    }

    private void recordFailure(Long id, RuntimeException failure) {
        log.warn("outbox.delivery.failed outboxEventId={} reason={}", id, failure.toString());
        metrics.outboxFailed();
        try {
            executor.recordFailure(id, failure.toString());
        } catch (RuntimeException e) {
            log.error("outbox.failure.unrecorded outboxEventId={}", id, e);
        }
    }
}
