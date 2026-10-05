package com.example.booking.repository;

import com.example.booking.entity.Event;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Loads the event with {@code SELECT ... FOR SHARE}. Concurrent bookings share the lock, while an
     * update of the event waits for them, so a booking can never commit against a stale event status.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForShare(@Param("id") Long id);
}
