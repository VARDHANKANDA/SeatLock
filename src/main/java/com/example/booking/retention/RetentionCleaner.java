package com.example.booking.retention;

import com.example.booking.config.RetentionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes old finished rows so the outbox and idempotency tables do not grow without bound.
 *
 * <p>Only rows that can no longer matter are removed: PROCESSED outbox events past the retention age, and
 * idempotency keys past their retention. PENDING events (including those waiting to be retried) are never
 * touched. FAILED events are kept for inspection unless a failed-event retention is configured. Deletion is in
 * bounded batches, one transaction each, and skips rows another cleaner instance holds, so several instances
 * can run it at once.
 *
 * <p>Forgetting an idempotency key means a retry of that very old request would be treated as a new request.
 * The retention window is therefore the period in which retries are deduplicated.
 */
@Component
public class RetentionCleaner {

    /** Upper bound on batches per run, so one run cannot monopolise the scheduler thread. */
    static final int MAX_BATCHES_PER_RUN = 200;

    private static final Logger log = LoggerFactory.getLogger(RetentionCleaner.class);

    public record Result(int processedOutbox, int failedOutbox, int idempotencyKeys) {
    }

    private final RetentionBatches batches;
    private final RetentionProperties properties;
    private final Clock clock;

    RetentionCleaner(RetentionBatches batches, RetentionProperties properties, Clock clock) {
        this.batches = batches;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${app.retention.cleanup-interval-minutes}",
            fixedDelayString = "${app.retention.cleanup-interval-minutes}", timeUnit = TimeUnit.MINUTES)
    public void clean() {
        try {
            Result result = cleanOnce();
            if (result.processedOutbox() + result.failedOutbox() + result.idempotencyKeys() > 0) {
                log.info("retention.cleaned processedOutbox={} failedOutbox={} idempotencyKeys={}",
                        result.processedOutbox(), result.failedOutbox(), result.idempotencyKeys());
            }
        } catch (RuntimeException e) {
            log.error("retention.failed", e);
        }
    }

    public Result cleanOnce() {
        Instant now = clock.instant();
        return new Result(
                drain(properties.outboxProcessedDays() > 0, now.minus(Duration.ofDays(properties.outboxProcessedDays())),
                        batches::deleteProcessedOutbox),
                drain(properties.outboxFailedDays() > 0, now.minus(Duration.ofDays(properties.outboxFailedDays())),
                        batches::deleteFailedOutbox),
                drain(properties.idempotencyHours() > 0, now.minus(Duration.ofHours(properties.idempotencyHours())),
                        batches::deleteIdempotencyKeys));
    }

    private int drain(boolean enabled, Instant cutoff, BiFunction<Instant, Integer, Integer> deleteBatch) {
        if (!enabled) {
            return 0;
        }
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = deleteBatch.apply(cutoff, properties.batchSize());
            total += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
