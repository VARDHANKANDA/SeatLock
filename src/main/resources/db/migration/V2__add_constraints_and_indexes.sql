-- At most one CONFIRMED booking per seat. Cancelled bookings are kept as history
-- and are excluded from the index, so a cancelled seat can be booked again.
CREATE UNIQUE INDEX uq_bookings_confirmed_seat ON bookings (seat_id) WHERE status = 'CONFIRMED';

CREATE INDEX idx_bookings_user_created ON bookings (user_id, created_at DESC);
CREATE INDEX idx_bookings_event ON bookings (event_id);
CREATE INDEX idx_bookings_seat ON bookings (seat_id);

CREATE INDEX idx_events_event_date_time ON events (event_date_time);

CREATE INDEX idx_seats_event_status ON seats (event_id, status);

CREATE INDEX idx_idempotency_booking ON idempotency_records (booking_id);
