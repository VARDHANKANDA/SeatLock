package com.example.booking.service;

import com.example.booking.config.ReservationProperties;
import com.example.booking.repository.ReservationHoldRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically releases seats whose hold deadline has passed. State lives in PostgreSQL only, so any number
 * of application instances can run this; {@link ReservationHoldService#expire} re-checks every hold under the
 * seat lock and skips holds that were confirmed, cancelled or already expired in the meantime.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final ReservationHoldRepository holdRepository;
    private final ReservationHoldService holdService;
    private final ReservationProperties properties;
    private final Clock clock;

    public HoldExpirySweeper(ReservationHoldRepository holdRepository, ReservationHoldService holdService,
                             ReservationProperties properties, Clock clock) {
        this.holdRepository = holdRepository;
        this.holdService = holdService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${app.reservation.expiry-sweep-interval-seconds}",
            fixedDelayString = "${app.reservation.expiry-sweep-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    public void sweep() {
        try {
            int expired = sweepOnce();
            if (expired > 0) {
                log.info("hold.sweep.completed expired={}", expired);
            }
        } catch (RuntimeException e) {
            log.error("hold.sweep.failed", e);
        }
    }

    /** Expires lapsed holds in bounded batches, one short transaction per hold; returns how many it expired. */
    public int sweepOnce() {
        int total = 0;
        while (true) {
            List<Long> lapsed = holdRepository.findLapsedIds(clock.instant(),
                    PageRequest.of(0, properties.expiryBatchSize()));
            int expiredInBatch = 0;
            for (Long holdId : lapsed) {
                try {
                    if (holdService.expire(holdId)) {
                        expiredInBatch++;
                    }
                } catch (RuntimeException e) {
                    log.warn("hold.expire.failed holdId={} reason={}", holdId, e.toString());
                }
            }
            total += expiredInBatch;
            if (lapsed.size() < properties.expiryBatchSize() || expiredInBatch == 0) {
                return total;
            }
        }
    }
}
