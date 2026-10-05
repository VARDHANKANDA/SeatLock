package com.example.booking.repository;

import com.example.booking.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /** Records the notification once per outbox event; returns 0 if it was already recorded. */
    @Modifying
    @Query(value = """
            INSERT INTO notification_log (outbox_event_id, user_id, event_type, message)
            VALUES (:outboxEventId, :userId, :eventType, :message)
            ON CONFLICT (outbox_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("outboxEventId") Long outboxEventId, @Param("userId") Long userId,
                       @Param("eventType") String eventType, @Param("message") String message);
}
