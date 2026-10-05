package com.example.booking.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @Min(1) int loginPerMinute,
        @Min(1) int registerPerMinute,
        @Min(1) int bookingPerMinute) {
}
