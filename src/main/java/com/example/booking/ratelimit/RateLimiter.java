package com.example.booking.ratelimit;

import com.example.booking.config.AppMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window request counter shared through Redis, so the limit holds across application instances.
 *
 * <p>The increment and the window expiry run in one Lua script, which keeps a crash between the two steps
 * from leaving a counter that never expires. The script also repairs a counter that exists without an expiry,
 * so a client can never be locked out permanently.
 *
 * <p>The window is fixed, starting at a client's first request: up to {@code limit} requests are allowed per
 * window, so a burst straddling a window boundary can briefly reach twice the limit. That is the accepted
 * trade-off for a single atomic round trip.
 *
 * <p>Failure policy: <b>fail open</b>. If Redis cannot be reached the request is allowed. Rate limiting is
 * abuse protection; logging in and booking must keep working during a Redis outage, and booking correctness
 * never depends on this class because it is enforced by PostgreSQL.
 */
@Component
public class RateLimiter {

    static final long WINDOW_SECONDS = 60;

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            local ttl = redis.call('TTL', KEYS[1])
            if current == 1 or ttl < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {current, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final AppMetrics metrics;

    public RateLimiter(StringRedisTemplate redis, AppMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision permitted() {
            return new Decision(true, 0);
        }

        static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    public Decision check(String bucket, String identity, int limit) {
        try {
            List<?> result = redis.execute(INCREMENT_WITH_EXPIRY, List.of("rl:" + bucket + ":" + identity),
                    String.valueOf(WINDOW_SECONDS));
            long count = ((Number) result.get(0)).longValue();
            long ttl = ((Number) result.get(1)).longValue();
            if (count > limit) {
                metrics.rateLimitExceeded();
                return Decision.rejected(ttl > 0 ? ttl : WINDOW_SECONDS);
            }
            return Decision.permitted();
        } catch (RuntimeException e) {
            log.warn("ratelimit.unavailable bucket={} reason={}", bucket, e.getClass().getSimpleName());
            return Decision.permitted();
        }
    }
}
