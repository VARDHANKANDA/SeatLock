package com.example.booking.entity;

public enum OutboxEventType {
    BOOKING_CONFIRMED,
    BOOKING_CANCELLED,
    RESERVATION_HELD,
    RESERVATION_EXPIRED,
    RESERVATION_CANCELLED
}
