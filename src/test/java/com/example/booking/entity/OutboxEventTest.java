package com.example.booking.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final OutboxEvent event =
            new OutboxEvent(AggregateType.BOOKING, 1L, OutboxEventType.BOOKING_CONFIRMED, "{}", NOW);

    @Test
    void startsPendingAndDueImmediately() {
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void failuresBackOffExponentiallyAndStayPending() {
        event.recordFailure("boom", NOW, 5);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(5)));
        event.recordFailure("boom", NOW, 5);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(10)));
        event.recordFailure("boom", NOW, 5);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(20)));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(3);
    }

    @Test
    void backoffIsCapped() {
        for (int i = 0; i < 20; i++) {
            event.recordFailure("boom", NOW, 100);
        }

        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(300)));
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttempts() {
        for (int i = 0; i < 3; i++) {
            event.recordFailure("boom", NOW, 3);
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
    }

    @Test
    void longErrorsAreTruncatedToTheColumnSize() {
        event.recordFailure("x".repeat(5000), NOW, 5);

        assertThat(event.getLastError()).hasSize(1000);
    }

    @Test
    void successClearsTheLastError() {
        event.recordFailure("boom", NOW, 5);

        event.markProcessed(NOW.plusSeconds(30));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.getLastError()).isNull();
    }
}
