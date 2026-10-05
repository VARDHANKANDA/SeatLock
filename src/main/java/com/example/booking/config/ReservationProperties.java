package com.example.booking.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.reservation")
public record ReservationProperties(
        @Min(1) long holdDurationSeconds,
        @Min(1) long expirySweepIntervalSeconds,
        @Min(1) int expiryBatchSize) {
}
