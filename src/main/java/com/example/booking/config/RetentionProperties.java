package com.example.booking.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long finished rows are kept. A value of 0 means "do not delete" for every retention setting except
 * {@code batchSize} and {@code cleanupIntervalMinutes}.
 *
 * @param outboxProcessedDays    age after which PROCESSED outbox events (and their notifications) are deleted
 * @param outboxFailedDays       age after which FAILED outbox events are deleted; 0 keeps them for inspection
 * @param idempotencyHours       how long an idempotency key is remembered
 * @param batchSize              rows deleted per transaction
 * @param cleanupIntervalMinutes how often the cleaner runs
 */
@Validated
@ConfigurationProperties(prefix = "app.retention")
public record RetentionProperties(
        @Min(0) int outboxProcessedDays,
        @Min(0) int outboxFailedDays,
        @Min(0) int idempotencyHours,
        @Min(1) int batchSize,
        @Min(1) long cleanupIntervalMinutes) {
}
