package com.example.booking.dto;

import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import java.time.Instant;

public record BookingResponse(
        Long id,
        Long eventId,
        Long seatId,
        String seatNumber,
        BookingStatus status,
        Instant createdAt,
        Instant cancelledAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getEventId(),
                booking.getSeat().getId(),
                booking.getSeat().getSeatNumber(),
                booking.getStatus(),
                booking.getCreatedAt(),
                booking.getCancelledAt());
    }
}
