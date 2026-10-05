package com.example.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "events")
public class Event extends AuditedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "event_date_time", nullable = false)
    private Instant eventDateTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    protected Event() {
    }

    public Event(String name, String description, String venue, Instant eventDateTime, EventStatus status) {
        update(name, description, venue, eventDateTime, status);
    }

    public void update(String name, String description, String venue, Instant eventDateTime, EventStatus status) {
        this.name = name;
        this.description = description;
        this.venue = venue;
        this.eventDateTime = eventDateTime;
        this.status = status;
    }

    public boolean isBookableAt(Instant now) {
        return status == EventStatus.SCHEDULED && eventDateTime.isAfter(now);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getEventDateTime() {
        return eventDateTime;
    }

    public EventStatus getStatus() {
        return status;
    }
}
