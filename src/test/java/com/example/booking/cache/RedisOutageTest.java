package com.example.booking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the application with Redis pointed at a closed port, i.e. a real outage. Cached reads must fall back
 * to PostgreSQL, rate limiting must fail open, and booking correctness must be unaffected.
 */
class RedisOutageTest extends IntegrationTestSupport {

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 1);
    }

    private String user;
    private String admin;

    @BeforeEach
    void setUp() {
        user = bearerFor(saveUser("user@example.com", Role.USER));
        admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));
    }

    @Test
    void eventReadsFallBackToTheDatabase() throws Exception {
        Event event = saveEvent();

        mockMvc.perform(get("/api/v1/events/" + event.getId()).header(AUTHORIZATION, user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Concert"));
        mockMvc.perform(get("/api/v1/events").header(AUTHORIZATION, user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void eventWritesStillSucceedWhenInvalidationIsImpossible() throws Exception {
        Event event = saveEvent();

        mockMvc.perform(put("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "name", "Renamed", "venue", "Hall", "status", "SCHEDULED",
                                "eventDateTime", Instant.now().plus(Duration.ofDays(3)).toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void loginAndRegistrationKeepWorkingBecauseRateLimitingFailsOpen() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ada", "email", "ada@example.com", "password", "Secret123"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ada@example.com", "password", "Secret123"))))
                .andExpect(status().isOk());
    }

    @Test
    void bookingStaysCorrectWhileRedisIsDown() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String other = bearerFor(saveUser("other@example.com", Role.USER));

        book(user, event, seat).andExpect(status().isCreated());
        book(other, event, seat).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_BOOKED"));

        assertThat(jdbc.queryForObject("select count(*) from bookings where status = 'CONFIRMED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void holdsStayCorrectWhileRedisIsDown() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String other = bearerFor(saveUser("other@example.com", Role.USER));

        mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void healthStaysUpBecauseRedisIsNotARequiredDependency() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private ResultActions book(String token, Event event, Seat seat)
            throws Exception {
        return mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))));
    }
}
