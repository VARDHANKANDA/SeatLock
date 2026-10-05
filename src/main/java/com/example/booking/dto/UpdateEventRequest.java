package com.example.booking.dto;

import com.example.booking.entity.EventStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record UpdateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 200) String venue,
        @NotNull Instant eventDateTime,
        @NotNull EventStatus status) {
}
