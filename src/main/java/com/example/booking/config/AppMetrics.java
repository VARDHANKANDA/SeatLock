package com.example.booking.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business counters, exposed through the Actuator metrics endpoint (ADMIN only). All counters are registered
 * at startup so they are visible at zero before the first event.
 */
@Component
public class AppMetrics {

    private final Counter bookingSuccess;
    private final Counter bookingConflict;
    private final Counter holdSuccess;
    private final Counter holdConflict;
    private final Counter holdExpired;
    private final Counter rateLimitExceeded;
    private final Counter outboxProcessed;
    private final Counter outboxFailed;
    private final Counter cacheHit;
    private final Counter cacheMiss;

    public AppMetrics(MeterRegistry registry) {
        this.bookingSuccess = registry.counter("booking.success");
        this.bookingConflict = registry.counter("booking.conflict");
        this.holdSuccess = registry.counter("reservation.hold.success");
        this.holdConflict = registry.counter("reservation.hold.conflict");
        this.holdExpired = registry.counter("reservation.hold.expired");
        this.rateLimitExceeded = registry.counter("rate_limit.exceeded");
        this.outboxProcessed = registry.counter("outbox.processed");
        this.outboxFailed = registry.counter("outbox.failed");
        this.cacheHit = registry.counter("cache.hit");
        this.cacheMiss = registry.counter("cache.miss");
    }

    public void bookingSuccess() {
        bookingSuccess.increment();
    }

    public void bookingConflict() {
        bookingConflict.increment();
    }

    public void holdSuccess() {
        holdSuccess.increment();
    }

    public void holdConflict() {
        holdConflict.increment();
    }

    public void holdExpired() {
        holdExpired.increment();
    }

    public void rateLimitExceeded() {
        rateLimitExceeded.increment();
    }

    public void outboxProcessed() {
        outboxProcessed.increment();
    }

    public void outboxFailed() {
        outboxFailed.increment();
    }

    public void cacheHit() {
        cacheHit.increment();
    }

    public void cacheMiss() {
        cacheMiss.increment();
    }
}
