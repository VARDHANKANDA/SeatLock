package com.example.booking.dto;

import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import java.time.Instant;

public record EventResponse(
        Long id,
        String name,
        String description,
        String venue,
        Instant eventDateTime,
        EventStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getVenue(),
                event.getEventDateTime(),
                event.getStatus(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
