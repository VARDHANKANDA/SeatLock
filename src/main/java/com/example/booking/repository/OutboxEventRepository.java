package com.example.booking.repository;

import com.example.booking.entity.OutboxEvent;
import com.example.booking.entity.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select e.id from OutboxEvent e "
            + "where e.status = com.example.booking.entity.OutboxStatus.PENDING and e.nextAttemptAt <= :now "
            + "order by e.nextAttemptAt, e.id")
    List<Long> findDueIds(@Param("now") Instant now, Pageable pageable);

    /** Locks a still-pending event for this worker; returns empty if another worker holds it or it is done. */
    @Query(value = "select * from outbox_event where id = :id and status = 'PENDING' for update skip locked",
            nativeQuery = true)
    Optional<OutboxEvent> lockPendingById(@Param("id") Long id);

    long countByStatus(OutboxStatus status);

    /**
     * Deletes up to {@code limit} PROCESSED events processed before {@code cutoff}, together with their
     * notification rows (which reference them). Rows locked by another transaction are skipped.
     */
    @Modifying
    @Query(value = """
            WITH doomed AS (
                SELECT id FROM outbox_event
                WHERE status = 'PROCESSED' AND processed_at < :cutoff
                ORDER BY processed_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED),
            removed_notifications AS (
                DELETE FROM notification_log WHERE outbox_event_id IN (SELECT id FROM doomed))
            DELETE FROM outbox_event WHERE id IN (SELECT id FROM doomed)
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Same as {@link #deleteProcessedBefore} for FAILED events, by creation time. */
    @Modifying
    @Query(value = """
            WITH doomed AS (
                SELECT id FROM outbox_event
                WHERE status = 'FAILED' AND created_at < :cutoff
                ORDER BY created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED),
            removed_notifications AS (
                DELETE FROM notification_log WHERE outbox_event_id IN (SELECT id FROM doomed))
            DELETE FROM outbox_event WHERE id IN (SELECT id FROM doomed)
            """, nativeQuery = true)
    int deleteFailedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
