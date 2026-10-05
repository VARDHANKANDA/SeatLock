package com.example.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "reservation_holds")
public class ReservationHold extends AuditedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false, updatable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "booking_id")
    private Long bookingId;

    protected ReservationHold() {
    }

    public ReservationHold(Long userId, Seat seat, Instant expiresAt) {
        this.userId = userId;
        this.eventId = seat.getEventId();
        this.seat = seat;
        this.expiresAt = expiresAt;
        this.status = HoldStatus.HELD;
    }

    public boolean isActive() {
        return status == HoldStatus.HELD;
    }

    public boolean isLapsedAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void expire() {
        this.status = HoldStatus.EXPIRED;
    }

    public void cancel() {
        this.status = HoldStatus.CANCELLED;
    }

    public void convertTo(Long bookingId) {
        this.status = HoldStatus.CONVERTED;
        this.bookingId = bookingId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Seat getSeat() {
        return seat;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getBookingId() {
        return bookingId;
    }
}
