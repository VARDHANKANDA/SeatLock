package com.example.booking.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.booking.config.RetentionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetentionCleanerTest {

    private static final Instant NOW = Instant.parse("2030-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RetentionBatches batches;

    private RetentionCleaner cleaner(int processedDays, int failedDays, int idempotencyHours, int batchSize) {
        return new RetentionCleaner(batches,
                new RetentionProperties(processedDays, failedDays, idempotencyHours, batchSize, 60), CLOCK);
    }

    @Test
    void everyRuleSetToZeroDeletesNothing() {
        RetentionCleaner.Result result = cleaner(0, 0, 0, 10).cleanOnce();

        assertThat(result).isEqualTo(new RetentionCleaner.Result(0, 0, 0));
        verifyNoInteractions(batches);
    }

    @Test
    void failedEventsAreKeptUnlessAFailedRetentionIsConfigured() {
        when(batches.deleteProcessedOutbox(any(), anyInt())).thenReturn(0);
        when(batches.deleteIdempotencyKeys(any(), anyInt())).thenReturn(0);

        cleaner(14, 0, 24, 10).cleanOnce();

        verify(batches, never()).deleteFailedOutbox(any(), anyInt());
    }

    @Test
    void cutoffsAreDerivedFromTheConfiguredAges() {
        cleaner(14, 30, 24, 10).cleanOnce();

        verify(batches).deleteProcessedOutbox(NOW.minus(Duration.ofDays(14)), 10);
        verify(batches).deleteFailedOutbox(NOW.minus(Duration.ofDays(30)), 10);
        verify(batches).deleteIdempotencyKeys(NOW.minus(Duration.ofHours(24)), 10);
    }

    @Test
    void keepsDeletingWhileBatchesAreFullAndStopsAtThePartialBatch() {
        when(batches.deleteProcessedOutbox(any(), anyInt())).thenReturn(10, 10, 4);

        RetentionCleaner.Result result = cleaner(14, 0, 0, 10).cleanOnce();

        assertThat(result.processedOutbox()).isEqualTo(24);
        verify(batches, times(3)).deleteProcessedOutbox(any(), anyInt());
    }

    @Test
    void aSingleRunIsBoundedEvenIfEveryBatchIsFull() {
        when(batches.deleteProcessedOutbox(any(), anyInt())).thenReturn(10);

        RetentionCleaner.Result result = cleaner(14, 0, 0, 10).cleanOnce();

        verify(batches, times(RetentionCleaner.MAX_BATCHES_PER_RUN)).deleteProcessedOutbox(any(), anyInt());
        assertThat(result.processedOutbox()).isEqualTo(10 * RetentionCleaner.MAX_BATCHES_PER_RUN);
    }

    @Test
    void aFailureIsLoggedByTheScheduledEntryPointAndDoesNotEscape() {
        when(batches.deleteProcessedOutbox(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        cleaner(14, 0, 0, 10).clean();

        verify(batches, atLeastOnce()).deleteProcessedOutbox(any(), anyInt());
    }
}
