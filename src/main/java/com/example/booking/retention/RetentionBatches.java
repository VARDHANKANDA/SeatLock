package com.example.booking.retention;

import com.example.booking.repository.IdempotencyRecordRepository;
import com.example.booking.repository.OutboxEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bounded delete per call, each in its own short transaction, so retention never holds locks for long and
 * a failure loses at most one batch. They are in their own bean so calls go through the transaction proxy.
 */
@Component
class RetentionBatches {

    private final OutboxEventRepository outboxRepository;
    private final IdempotencyRecordRepository idempotencyRepository;

    RetentionBatches(OutboxEventRepository outboxRepository, IdempotencyRecordRepository idempotencyRepository) {
        this.outboxRepository = outboxRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    @Transactional
    int deleteProcessedOutbox(Instant cutoff, int limit) {
        return outboxRepository.deleteProcessedBefore(cutoff, limit);
    }

    @Transactional
    int deleteFailedOutbox(Instant cutoff, int limit) {
        return outboxRepository.deleteFailedBefore(cutoff, limit);
    }

    @Transactional
    int deleteIdempotencyKeys(Instant cutoff, int limit) {
        return idempotencyRepository.deleteCreatedBefore(cutoff, limit);
    }
}
