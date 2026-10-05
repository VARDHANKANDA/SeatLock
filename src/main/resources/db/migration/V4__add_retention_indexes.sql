-- Support the retention cleaner, which deletes old finished rows in bounded batches.
CREATE INDEX idx_outbox_processed_at ON outbox_event (processed_at) WHERE status = 'PROCESSED';
CREATE INDEX idx_outbox_failed_created ON outbox_event (created_at) WHERE status = 'FAILED';
CREATE INDEX idx_idempotency_created ON idempotency_records (created_at);
