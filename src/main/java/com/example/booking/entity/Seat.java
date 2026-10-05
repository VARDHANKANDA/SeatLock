package com.example.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "seats")
public class Seat extends AuditedEntity {

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "seat_number", nullable = false, updatable = false, length = 20)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    protected Seat() {
    }

    public Seat(Long eventId, String seatNumber) {
        this.eventId = eventId;
        this.seatNumber = seatNumber;
        this.status = SeatStatus.AVAILABLE;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    public boolean isHeld() {
        return status == SeatStatus.HELD;
    }

    public void markHeld() {
        this.status = SeatStatus.HELD;
    }

    public void markBooked() {
        this.status = SeatStatus.BOOKED;
    }

    public void markAvailable() {
        this.status = SeatStatus.AVAILABLE;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }
}
