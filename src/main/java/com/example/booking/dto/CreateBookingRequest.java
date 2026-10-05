package com.example.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateBookingRequest(
        @NotNull @Positive Long eventId,
        @NotNull @Positive Long seatId) {
}
