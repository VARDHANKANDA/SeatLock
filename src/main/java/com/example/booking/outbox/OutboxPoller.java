package com.example.booking.outbox;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxProcessor processor;

    public OutboxPoller(OutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(initialDelayString = "${app.outbox.poll-interval-seconds}",
            fixedDelayString = "${app.outbox.poll-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    public void poll() {
        try {
            int delivered = processor.processBatch();
            if (delivered > 0) {
                log.info("outbox.batch.processed count={}", delivered);
            }
        } catch (RuntimeException e) {
            log.error("outbox.poll.failed", e);
        }
    }
}
