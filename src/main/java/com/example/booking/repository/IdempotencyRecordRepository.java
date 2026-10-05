package com.example.booking.repository;

import com.example.booking.entity.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * Atomically claims a key. If another transaction is inserting the same key, this statement
     * waits for it to finish. Returns 0 when the key already exists.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records (user_id, idempotency_key)
            VALUES (:userId, :key)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("userId") Long userId, @Param("key") String key);

    @Modifying
    @Query("update IdempotencyRecord r set r.bookingId = :bookingId where r.userId = :userId and r.requestKey = :key")
    int attachBooking(@Param("userId") Long userId, @Param("key") String key, @Param("bookingId") Long bookingId);

    /** Deletes up to {@code limit} keys created before {@code cutoff}; rows locked by another transaction are skipped. */
    @Modifying
    @Query(value = """
            DELETE FROM idempotency_records WHERE id IN (
                SELECT id FROM idempotency_records
                WHERE created_at < :cutoff
                ORDER BY created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Query("select r.bookingId from IdempotencyRecord r where r.userId = :userId and r.requestKey = :key")
    Optional<Long> findBookingId(@Param("userId") Long userId, @Param("key") String key);
}
