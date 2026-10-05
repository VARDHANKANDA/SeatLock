package com.example.booking.repository;

import com.example.booking.entity.Booking;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("select b from Booking b join fetch b.seat where b.id = :id")
    Optional<Booking> findWithSeatById(@Param("id") Long id);

    /** Locks only the booking row, so lock acquisition order stays booking, then seat. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "select b from Booking b join fetch b.seat where b.userId = :userId",
            countQuery = "select count(b) from Booking b where b.userId = :userId")
    Page<Booking> findByUserId(@Param("userId") Long userId, Pageable pageable);

    boolean existsByEventId(Long eventId);
}
