package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.config.AppMetrics;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.rate-limit.login-per-minute=3",
        "app.rate-limit.register-per-minute=2",
        "app.rate-limit.booking-per-minute=3"
})
class RateLimitTest extends IntegrationTestSupport {

    @Test
    void loginUnderTheLimitIsNotThrottled() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("nobody@example.com").andExpect(status().isUnauthorized());
        }
    }

    @Test
    void loginOverTheLimitReturns429WithRetryAfterAndStandardError() throws Exception {
        double exceeded = counter();
        for (int i = 0; i < 3; i++) {
            login("nobody@example.com").andExpect(status().isUnauthorized());
        }

        login("nobody@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.timestamp").exists());
        assertThat(counter()).isEqualTo(exceeded + 1);
    }

    @Test
    void retryAfterMatchesTheRemainingWindow() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("nobody@example.com");
        }

        String retryAfter = login("nobody@example.com").andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(redis.getExpire("rl:login:127.0.0.1")).isBetween(1L, 60L);
    }

    @Test
    void registrationHasItsOwnLowerLimit() throws Exception {
        register("a@example.com").andExpect(status().isCreated());
        register("b@example.com").andExpect(status().isCreated());

        register("c@example.com").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(userRepository.existsByEmail("c@example.com")).isFalse();
    }

    @Test
    void loginAndRegistrationBucketsAreIndependent() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("nobody@example.com");
        }

        register("a@example.com").andExpect(status().isCreated());
    }

    @Test
    void bookingLimitIsPerUserAndCoversHolds() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String busy = bearerFor(saveUser("busy@example.com", Role.USER));
        String other = bearerFor(saveUser("other@example.com", Role.USER));

        book(busy, event, seat).andExpect(status().isCreated());
        book(busy, event, seat).andExpect(status().isConflict());
        hold(busy, event, seat).andExpect(status().isConflict());

        book(busy, event, seat).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        hold(busy, event, seat).andExpect(status().isTooManyRequests());
        book(other, event, seat).andExpect(status().isConflict());
    }

    @Test
    void unauthenticatedBookingRequestsAreRejectedNotCounted() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void readEndpointsAreNotRateLimited() throws Exception {
        String user = bearerFor(saveUser("reader@example.com", Role.USER));

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/v1/events").header(AUTHORIZATION, user)).andExpect(status().isOk());
        }
    }

    @Test
    void limitIsSharedBetweenLimiterInstancesUsingTheSameRedis() {
        AppMetrics metrics = new AppMetrics(meterRegistry);
        RateLimiter instanceA = new RateLimiter(redis, metrics);
        RateLimiter instanceB = new RateLimiter(redis, metrics);

        assertThat(instanceA.check("shared", "client", 3).allowed()).isTrue();
        assertThat(instanceB.check("shared", "client", 3).allowed()).isTrue();
        assertThat(instanceA.check("shared", "client", 3).allowed()).isTrue();

        assertThat(instanceB.check("shared", "client", 3).allowed()).isFalse();
        assertThat(instanceA.check("shared", "client", 3).allowed()).isFalse();
        assertThat(instanceA.check("shared", "someone-else", 3).allowed()).isTrue();
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", "Wrong-password1"))));
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Tester", "email", email, "password", "Secret123"))));
    }

    private ResultActions book(String token, Event event, Seat seat) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))));
    }

    private ResultActions hold(String token, Event event, Seat seat) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))));
    }

    private double counter() {
        var counter = meterRegistry.find("rate_limit.exceeded").counter();
        return counter == null ? 0 : counter.count();
    }
}
