package com.example.booking.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code lockTimeoutMs} of 0 disables the timeout (PostgreSQL waits for locks indefinitely). */
@Validated
@ConfigurationProperties(prefix = "app.locking")
public record LockProperties(@Min(0) long lockTimeoutMs) {
}
