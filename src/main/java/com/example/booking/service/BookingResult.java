package com.example.booking.service;

import com.example.booking.dto.BookingResponse;

/** Outcome of a booking request; {@code replayed} is true when an idempotency key matched an earlier request. */
public record BookingResult(BookingResponse booking, boolean replayed) {
}
