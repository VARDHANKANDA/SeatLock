package com.example.booking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class RedisCacheTest extends IntegrationTestSupport {

    private String user;
    private String admin;

    @BeforeEach
    void setUp() {
        user = bearerFor(saveUser("user@example.com", Role.USER));
        admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));
    }

    @Test
    void firstReadMissesThenPopulatesCacheWithTtl() throws Exception {
        Event event = saveEvent();
        double misses = counter("cache.miss");

        getEvent(event).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Concert"));

        assertThat(counter("cache.miss")).isEqualTo(misses + 1);
        assertThat(redis.hasKey("event:" + event.getId())).isTrue();
        assertThat(redis.getExpire("event:" + event.getId())).isBetween(1L, 60L);
    }

    @Test
    void secondReadIsServedFromCacheWithoutTouchingTheDatabase() throws Exception {
        Event event = saveEvent();
        getEvent(event).andExpect(status().isOk());
        double hits = counter("cache.hit");
        jdbc.update("update events set name = 'Changed behind the cache' where id = ?", event.getId());

        getEvent(event).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Concert"));

        assertThat(counter("cache.hit")).isEqualTo(hits + 1);
    }

    @Test
    void updatingAnEventInvalidatesItsCacheEntry() throws Exception {
        Event event = saveEvent();
        getEvent(event).andExpect(jsonPath("$.name").value("Concert"));

        mockMvc.perform(put("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "name", "Renamed", "venue", "Hall", "status", "SCHEDULED",
                                "eventDateTime", Instant.now().plus(Duration.ofDays(3)).toString()))))
                .andExpect(status().isOk());

        assertThat(redis.hasKey("event:" + event.getId())).isFalse();
        getEvent(event).andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void deletingAnEventInvalidatesItsCacheEntry() throws Exception {
        Event event = saveEvent();
        getEvent(event).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        getEvent(event).andExpect(status().isNotFound());
    }

    @Test
    void creatingAnEventInvalidatesCachedListings() throws Exception {
        saveEvent();
        mockMvc.perform(get("/api/v1/events").header(AUTHORIZATION, user))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/api/v1/events").header(AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "New", "venue", "Hall",
                                "eventDateTime", Instant.now().plus(Duration.ofDays(9)).toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/events").header(AUTHORIZATION, user))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void repeatedListingIsServedFromCache() throws Exception {
        saveEvent();
        mockMvc.perform(get("/api/v1/events?page=0&size=5").header(AUTHORIZATION, user)).andExpect(status().isOk());
        double hits = counter("cache.hit");
        jdbc.update("update events set name = 'Changed behind the cache'");

        mockMvc.perform(get("/api/v1/events?page=0&size=5").header(AUTHORIZATION, user))
                .andExpect(jsonPath("$.content[0].name").value("Concert"));

        assertThat(counter("cache.hit")).isEqualTo(hits + 1);
    }

    @Test
    void unreadableCacheEntryFallsBackToTheDatabase() throws Exception {
        Event event = saveEvent();
        redis.opsForValue().set("event:" + event.getId(), "{not valid json");

        getEvent(event).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Concert"));
    }

    @Test
    void cancellingAnEventInvalidatesTheCachedCopySoReadersSeeTheCancellation() throws Exception {
        Event event = saveEvent();
        getEvent(event).andExpect(jsonPath("$.status").value("SCHEDULED"));
        assertThat(redis.hasKey("event:" + event.getId())).isTrue();

        mockMvc.perform(put("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "name", "Concert", "venue", "Main Hall", "status", "CANCELLED",
                                "eventDateTime", event.getEventDateTime().toString()))))
                .andExpect(status().isOk());

        assertThat(redis.hasKey("event:" + event.getId())).isFalse();
        getEvent(event).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void eachEventHasItsOwnCacheEntry() throws Exception {
        Event first = saveEvent();
        Event second = eventRepository.save(new Event("Other show", null, "Small Hall",
                Instant.now().plus(Duration.ofDays(9)), EventStatus.SCHEDULED));

        getEvent(first).andExpect(jsonPath("$.name").value("Concert"));
        getEvent(second).andExpect(jsonPath("$.name").value("Other show"));

        assertThat(redis.hasKey("event:" + first.getId())).isTrue();
        assertThat(redis.hasKey("event:" + second.getId())).isTrue();
        getEvent(first).andExpect(jsonPath("$.name").value("Concert"));
        getEvent(second).andExpect(jsonPath("$.name").value("Other show"));
    }

    @Test
    void aCachedResponseIsIdenticalToTheOneReadFromTheDatabase() throws Exception {
        Event event = saveEvent();
        String fromDatabase = getEvent(event).andReturn().getResponse().getContentAsString();

        String fromCache = getEvent(event).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(fromCache)).isEqualTo(objectMapper.readTree(fromDatabase));
        assertThat(objectMapper.readTree(fromCache).get("eventDateTime").asText()).isNotBlank();
    }

    @Test
    void notFoundResultsAreNotCached() throws Exception {
        getEvent(9999L).andExpect(status().isNotFound());

        assertThat(redis.hasKey("event:9999")).isFalse();
    }

    @Test
    void bookingDecisionsUseTheDatabaseEvenWhenTheCachedEventIsStale() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        getEvent(event).andExpect(jsonPath("$.status").value("SCHEDULED"));
        jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());
        getEvent(event).andExpect(jsonPath("$.status").value("SCHEDULED"));

        mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
        mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, user).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void seatAvailabilityIsNeverCached() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String path = "/api/v1/events/" + event.getId() + "/seats/available";
        mockMvc.perform(get(path).header(AUTHORIZATION, user)).andExpect(jsonPath("$.totalElements").value(1));

        jdbc.update("update seats set status = 'BOOKED' where id = ?", seat.getId());

        mockMvc.perform(get(path).header(AUTHORIZATION, user)).andExpect(jsonPath("$.totalElements").value(0));
    }

    private ResultActions getEvent(Event event) throws Exception {
        return getEvent(event.getId());
    }

    private ResultActions getEvent(long id) throws Exception {
        return mockMvc.perform(get("/api/v1/events/" + id).header(AUTHORIZATION, user));
    }

    private double counter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
