package com.example.booking.repository;

import com.example.booking.entity.ReservationHold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Holds only change state while the caller holds the lock on their seat row. These queries therefore take
 * no row locks of their own; they must be called after the seat has been locked.
 */
public interface ReservationHoldRepository extends JpaRepository<ReservationHold, Long> {

    /** Identity of a hold, read without loading the entity so it can be loaded fresh after the seat lock. */
    interface HoldRef {

        Long getUserId();

        Long getEventId();

        Long getSeatId();
    }

    @Query("select h.userId as userId, h.eventId as eventId, h.seat.id as seatId "
            + "from ReservationHold h where h.id = :id")
    Optional<HoldRef> findRefById(@Param("id") Long id);

    @Query("select h from ReservationHold h join fetch h.seat where h.id = :id")
    Optional<ReservationHold> findWithSeatById(@Param("id") Long id);

    @Query("select h from ReservationHold h join fetch h.seat "
            + "where h.seat.id = :seatId and h.status = com.example.booking.entity.HoldStatus.HELD")
    Optional<ReservationHold> findActiveBySeatId(@Param("seatId") Long seatId);

    @Query("select h.id from ReservationHold h "
            + "where h.status = com.example.booking.entity.HoldStatus.HELD and h.expiresAt <= :now "
            + "order by h.expiresAt")
    List<Long> findLapsedIds(@Param("now") Instant now, Pageable pageable);

    boolean existsByEventId(Long eventId);
}
