package com.example.booking.cache;

import com.example.booking.config.AppMetrics;
import com.example.booking.config.CacheProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache-aside storage for event reads in Redis.
 *
 * <p>The cache only ever serves read endpoints. Booking and hold transactions always read PostgreSQL, so a
 * stale or missing entry can never influence whether a seat is booked. Every operation swallows Redis
 * failures and behaves as a cache miss, so an outage degrades to database reads.
 *
 * <p>Writers evict after their transaction commits. An entry cached from a read that raced with a write can
 * stay stale until its TTL expires; that bound is the consistency contract for cached reads.
 */
@Component
public class EventCache {

    private static final Logger log = LoggerFactory.getLogger(EventCache.class);
    private static final String EVENT_KEY_PREFIX = "event:";
    private static final String PAGE_KEY_PREFIX = "events:page:";
    private static final String PAGE_VERSION_KEY = "events:page:version";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;
    private final AppMetrics metrics;

    public EventCache(StringRedisTemplate redis, ObjectMapper objectMapper, CacheProperties properties,
                      AppMetrics metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    public String eventKey(Long eventId) {
        return EVENT_KEY_PREFIX + eventId;
    }

    /** Page keys embed a version that is bumped on every event change, which invalidates all pages at once. */
    public String pageKey(int page, int size) {
        return PAGE_KEY_PREFIX + currentPageVersion() + ":" + page + ":" + size;
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        Optional<T> cached = read(key, type);
        if (cached.isPresent()) {
            metrics.cacheHit();
        } else {
            metrics.cacheMiss();
        }
        return cached;
    }

    public void put(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(properties.eventTtlSeconds()));
        } catch (Exception e) {
            log.warn("cache.unavailable operation=put reason={}", e.getClass().getSimpleName());
        }
    }

    /** Drops the event and every cached page once the surrounding transaction commits. */
    public void evictAfterCommit(Long eventId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(eventId);
                }
            });
        } else {
            evict(eventId);
        }
    }

    private void evict(Long eventId) {
        try {
            redis.delete(eventKey(eventId));
            redis.opsForValue().increment(PAGE_VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("cache.unavailable operation=evict reason={}", e.getClass().getSimpleName());
        }
    }

    private <T> Optional<T> read(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("cache.unavailable operation=get reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String currentPageVersion() {
        try {
            String version = redis.opsForValue().get(PAGE_VERSION_KEY);
            return version == null ? "0" : version;
        } catch (RuntimeException e) {
            return "0";
        }
    }
}
