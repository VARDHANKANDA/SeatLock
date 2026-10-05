package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.config.AppMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The limiter's Lua script against a real Redis: boundaries, expiry, atomicity under contention. */
class RateLimiterRedisTest extends IntegrationTestSupport {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(redis, new AppMetrics(meterRegistry));
    }

    @Test
    void theLimitItselfIsAllowedAndTheNextRequestIsRejected() {
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.check("login", "10.0.0.1", 5).allowed()).as("request %d", i).isTrue();
        }

        RateLimiter.Decision rejected = limiter.check("login", "10.0.0.1", 5);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, RateLimiter.WINDOW_SECONDS);
    }

    @Test
    void theWindowStartsAtTheFirstRequestAndRetryAfterShrinksWithTheRemainingTime() {
        limiter.check("login", "10.0.0.2", 1);
        limiter.check("login", "10.0.0.2", 1);
        long firstRetryAfter = limiter.check("login", "10.0.0.2", 1).retryAfterSeconds();
        redis.expire("rl:login:10.0.0.2", Duration.ofSeconds(20));

        long laterRetryAfter = limiter.check("login", "10.0.0.2", 1).retryAfterSeconds();

        assertThat(firstRetryAfter).isBetween(55L, 60L);
        assertThat(laterRetryAfter).isBetween(15L, 20L);
    }

    @Test
    void everyCounterGetsAnExpiryAndTheCountResetsWhenTheWindowEnds() {
        limiter.check("login", "10.0.0.3", 1);
        limiter.check("login", "10.0.0.3", 1);
        assertThat(redis.getExpire("rl:login:10.0.0.3")).isBetween(1L, RateLimiter.WINDOW_SECONDS);

        redis.expire("rl:login:10.0.0.3", Duration.ofSeconds(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> !redis.hasKey("rl:login:10.0.0.3"));

        assertThat(limiter.check("login", "10.0.0.3", 1).allowed()).as("new window").isTrue();
    }

    @Test
    void aCounterThatSomehowHasNoExpiryIsRepairedInsteadOfLockingTheClientOutForever() {
        redis.opsForValue().set("rl:login:10.0.0.4", "50");
        assertThat(redis.getExpire("rl:login:10.0.0.4")).isEqualTo(-1L);

        RateLimiter.Decision decision = limiter.check("login", "10.0.0.4", 5);

        assertThat(decision.allowed()).isFalse();
        assertThat(redis.getExpire("rl:login:10.0.0.4")).isBetween(1L, RateLimiter.WINDOW_SECONDS);
        assertThat(decision.retryAfterSeconds()).isBetween(1L, RateLimiter.WINDOW_SECONDS);
    }

    @Test
    void bucketsAndIdentitiesAreIndependent() {
        limiter.check("login", "10.0.0.5", 1);
        limiter.check("login", "10.0.0.5", 1);

        assertThat(limiter.check("login", "10.0.0.5", 1).allowed()).isFalse();
        assertThat(limiter.check("login", "10.0.0.6", 1).allowed()).isTrue();
        assertThat(limiter.check("register", "10.0.0.5", 1).allowed()).isTrue();
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        int threads = 100;
        int limit = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> call = () -> {
                    ready.countDown();
                    start.await();
                    return limiter.check("booking", "42", limit).allowed();
                };
                results.add(executor.submit(call));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                allowed += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }

            assertThat(allowed).isEqualTo(limit);
            assertThat(redis.opsForValue().get("rl:booking:42")).isEqualTo(String.valueOf(threads));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void separateLimiterInstancesShareOneCounter() {
        RateLimiter otherInstance = new RateLimiter(redis, new AppMetrics(meterRegistry));

        assertThat(limiter.check("login", "10.0.0.7", 2).allowed()).isTrue();
        assertThat(otherInstance.check("login", "10.0.0.7", 2).allowed()).isTrue();
        assertThat(limiter.check("login", "10.0.0.7", 2).allowed()).isFalse();
        assertThat(otherInstance.check("login", "10.0.0.7", 2).allowed()).isFalse();
    }
}
