package com.example.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateHoldRequest(
        @NotNull @Positive Long eventId,
        @NotNull @Positive Long seatId) {
}
