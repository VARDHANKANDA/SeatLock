package com.example.booking.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** FAILED events are only deleted when a failed-event retention is explicitly configured. */
@TestPropertySource(properties = "app.retention.outbox-failed-days=30")
class FailedEventRetentionTest extends IntegrationTestSupport {

    @Autowired
    private RetentionCleaner cleaner;

    @Test
    void failedEventsOlderThanTheConfiguredAgeAreDeletedAndNothingElseIs() {
        insert("FAILED", 40);
        insert("FAILED", 10);
        insert("PENDING", 400);
        insert("PROCESSED", 5);

        RetentionCleaner.Result result = cleaner.cleanOnce();

        assertThat(result.failedOutbox()).isEqualTo(1);
        assertThat(jdbc.queryForList("select status from outbox_event order by status", String.class))
                .containsExactly("FAILED", "PENDING", "PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where status = 'FAILED'", Integer.class))
                .as("the recent failed event stays").isEqualTo(1);
    }

    private void insert(String status, int ageDays) {
        jdbc.update("""
                insert into outbox_event (aggregate_type, aggregate_id, event_type, payload, status,
                                          created_at, processed_at, retry_count)
                values ('BOOKING', 1, 'BOOKING_CONFIRMED', '{}'::jsonb, ?, now() - make_interval(days => ?),
                        case when ? = 'PROCESSED' then now() - make_interval(days => ?) end, 0)
                """, status, ageDays, status, ageDays);
    }
}
