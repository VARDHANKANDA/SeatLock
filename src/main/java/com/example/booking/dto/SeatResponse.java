package com.example.booking.dto;

import com.example.booking.entity.Seat;
import com.example.booking.entity.SeatStatus;

public record SeatResponse(Long id, Long eventId, String seatNumber, SeatStatus status) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(), seat.getEventId(), seat.getSeatNumber(), seat.getStatus());
    }
}
