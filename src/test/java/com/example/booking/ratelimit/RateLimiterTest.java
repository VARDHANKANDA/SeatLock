package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.booking.config.AppMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    private SimpleMeterRegistry registry;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        limiter = new RateLimiter(redis, new AppMetrics(registry));
    }

    @Test
    void allowsRequestsUpToTheLimit() {
        stubCounter(3, 42);

        assertThat(limiter.check("login", "10.0.0.1", 3).allowed()).isTrue();
    }

    @Test
    void rejectsRequestsOverTheLimitWithRetryAfterFromTheWindow() {
        stubCounter(4, 42);

        RateLimiter.Decision decision = limiter.check("login", "10.0.0.1", 3);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(42);
        assertThat(registry.get("rate_limit.exceeded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void fallsBackToTheFullWindowWhenRedisReportsNoTtl() {
        stubCounter(9, -1);

        assertThat(limiter.check("login", "10.0.0.1", 3).retryAfterSeconds()).isEqualTo(RateLimiter.WINDOW_SECONDS);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failsOpenWhenRedisIsUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(limiter.check("login", "10.0.0.1", 3).allowed()).isTrue();
        assertThat(registry.get("rate_limit.exceeded").counter().count()).isZero();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void countersAreKeyedPerBucketAndIdentity() {
        when(redis.execute(any(RedisScript.class), eq(List.of("rl:booking:42")), any(Object[].class)))
                .thenReturn(List.of(1L, 60L));
        when(redis.execute(any(RedisScript.class), eq(List.of("rl:booking:43")), any(Object[].class)))
                .thenReturn(List.of(99L, 5L));

        assertThat(limiter.check("booking", "42", 30).allowed()).isTrue();
        assertThat(limiter.check("booking", "43", 30).allowed()).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubCounter(long count, long ttl) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(count, ttl));
    }
}
