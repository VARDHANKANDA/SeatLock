package com.example.booking.service;

import com.example.booking.config.LockProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds how long a booking, hold, cancellation or expiry transaction may wait for a row lock.
 *
 * <p>It sets PostgreSQL's {@code lock_timeout} with {@code set_config(..., is_local => true)}, which lasts only
 * until the current transaction ends. The setting therefore never leaks to other users of a pooled connection
 * and does not affect Flyway, startup or any transaction that does not call this. A timed-out statement fails
 * with SQLSTATE 55P03 and the whole transaction rolls back, so nothing is half-written.
 */
@Component
public class LockTimeouts {

    private final JdbcTemplate jdbc;
    private final LockProperties properties;

    public LockTimeouts(JdbcTemplate jdbc, LockProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Must be the first database call of the transaction, before any statement that can wait for a lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        if (properties.lockTimeoutMs() > 0) {
            jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class,
                    properties.lockTimeoutMs() + "ms");
        }
    }
}
