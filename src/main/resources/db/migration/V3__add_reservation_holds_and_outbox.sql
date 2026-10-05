-- Seats gain a HELD state while a reservation hold is active.
ALTER TABLE seats DROP CONSTRAINT ck_seats_status;
ALTER TABLE seats ADD CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED'));

CREATE TABLE reservation_holds (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT                   NOT NULL,
    event_id   BIGINT                   NOT NULL,
    seat_id    BIGINT                   NOT NULL,
    status     VARCHAR(20)              NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    booking_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_holds_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_holds_seat_event FOREIGN KEY (seat_id, event_id) REFERENCES seats (id, event_id),
    CONSTRAINT fk_holds_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT ck_holds_status CHECK (status IN ('HELD', 'EXPIRED', 'CONVERTED', 'CANCELLED')),
    CONSTRAINT ck_holds_booking CHECK ((status = 'CONVERTED') = (booking_id IS NOT NULL))
);

-- At most one active hold per seat. Expired, cancelled and converted holds stay as history.
CREATE UNIQUE INDEX uq_holds_active_seat ON reservation_holds (seat_id) WHERE status = 'HELD';
-- Serves the expiry sweep: only active holds are indexed, ordered by deadline.
CREATE INDEX idx_holds_expiry ON reservation_holds (expires_at) WHERE status = 'HELD';
CREATE INDEX idx_holds_user ON reservation_holds (user_id);
CREATE INDEX idx_holds_event ON reservation_holds (event_id);
CREATE INDEX idx_holds_seat ON reservation_holds (seat_id);

CREATE TABLE outbox_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  VARCHAR(50)              NOT NULL,
    aggregate_id    BIGINT                   NOT NULL,
    event_type      VARCHAR(50)              NOT NULL,
    payload         JSONB                    NOT NULL,
    status          VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    processed_at    TIMESTAMP WITH TIME ZONE,
    retry_count     INTEGER                  NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_outbox_processed_at CHECK ((status = 'PROCESSED') = (processed_at IS NOT NULL)),
    CONSTRAINT ck_outbox_retry_count CHECK (retry_count >= 0)
);

-- Serves the outbox worker: only unprocessed events are indexed, ordered by when they are due.
CREATE INDEX idx_outbox_due ON outbox_event (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_event (aggregate_type, aggregate_id);

-- One row per delivered notification; the unique key makes redelivery of an outbox event a no-op.
CREATE TABLE notification_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_event_id BIGINT                   NOT NULL,
    user_id         BIGINT                   NOT NULL,
    event_type      VARCHAR(50)              NOT NULL,
    message         VARCHAR(500)             NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_outbox FOREIGN KEY (outbox_event_id) REFERENCES outbox_event (id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_notification_outbox_event UNIQUE (outbox_event_id)
);

CREATE INDEX idx_notification_user ON notification_log (user_id);
