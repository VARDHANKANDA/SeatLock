package com.example.booking.dto;

import com.example.booking.entity.HoldStatus;
import com.example.booking.entity.ReservationHold;
import java.time.Instant;

public record HoldResponse(
        Long holdId,
        Long eventId,
        Long seatId,
        String seatNumber,
        HoldStatus status,
        Instant expiresAt) {

    public static HoldResponse from(ReservationHold hold) {
        return new HoldResponse(
                hold.getId(),
                hold.getEventId(),
                hold.getSeat().getId(),
                hold.getSeat().getSeatNumber(),
                hold.getStatus(),
                hold.getExpiresAt());
    }
}
