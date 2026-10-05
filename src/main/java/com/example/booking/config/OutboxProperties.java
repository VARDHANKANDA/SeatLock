package com.example.booking.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @Min(1) long pollIntervalSeconds,
        @Min(1) int batchSize,
        @Min(1) int maxAttempts) {
}
