package com.example.booking.repository;

import com.example.booking.entity.Seat;
import com.example.booking.entity.SeatStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Loads the seat with a pessimistic write lock, which Hibernate renders as {@code SELECT ... FOR NO KEY UPDATE}
     * on PostgreSQL. It conflicts with itself, so concurrent transactions queue; callers must hold a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    Page<Seat> findByEventId(Long eventId, Pageable pageable);

    Page<Seat> findByEventIdAndStatus(Long eventId, SeatStatus status, Pageable pageable);

    @Query("select s.seatNumber from Seat s where s.eventId = :eventId and s.seatNumber in :seatNumbers")
    List<String> findExistingSeatNumbers(@Param("eventId") Long eventId,
                                         @Param("seatNumbers") Collection<String> seatNumbers);
}
