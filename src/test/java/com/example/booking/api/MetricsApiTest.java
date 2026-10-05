package com.example.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Counters live in a registry shared by every test in the context, so assertions compare deltas. */
class MetricsApiTest extends IntegrationTestSupport {

    private String user;
    private String admin;
    private Event event;
    private Seat seat;

    @BeforeEach
    void setUp() {
        user = bearerFor(saveUser("user@example.com", Role.USER));
        admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));
        event = saveEvent();
        seat = saveSeat(event, "A1");
    }

    @Test
    void metricsAreRestrictedToAdministrators() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header(AUTHORIZATION, user)).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").header(AUTHORIZATION, admin)).andExpect(status().isOk());
    }

    @Test
    void everyBusinessCounterIsRegisteredBeforeItsFirstEvent() throws Exception {
        for (String name : new String[] {"booking.success", "booking.conflict", "reservation.hold.success",
                "reservation.hold.conflict", "reservation.hold.expired", "rate_limit.exceeded", "outbox.processed",
                "outbox.failed", "cache.hit", "cache.miss"}) {
            mockMvc.perform(get("/actuator/metrics/" + name).header(AUTHORIZATION, admin))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void bookingCountersAreExposedThroughActuator() throws Exception {
        double successBefore = counter("booking.success");
        double conflictBefore = counter("booking.conflict");

        mockMvc.perform(bookingRequest("/api/v1/bookings")).andExpect(status().isCreated());
        mockMvc.perform(bookingRequest("/api/v1/bookings")).andExpect(status().isConflict());

        mockMvc.perform(get("/actuator/metrics/booking.success").header(AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(successBefore + 1));
        mockMvc.perform(get("/actuator/metrics/booking.conflict").header(AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(conflictBefore + 1));
    }

    @Test
    void holdAndOutboxMetricsAreExposedThroughActuator() throws Exception {
        double holdsBefore = counter("reservation.hold.success");

        mockMvc.perform(bookingRequest("/api/v1/bookings/holds")).andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/metrics/reservation.hold.success").header(AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(holdsBefore + 1));
        mockMvc.perform(get("/actuator/metrics/outbox.pending").header(AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
    }

    @Test
    void holdConflictsAreCounted() throws Exception {
        double before = counter("reservation.hold.conflict");
        mockMvc.perform(bookingRequest("/api/v1/bookings/holds")).andExpect(status().isCreated());

        mockMvc.perform(bookingRequest("/api/v1/bookings/holds")).andExpect(status().isConflict());

        assertThat(counter("reservation.hold.conflict")).isEqualTo(before + 1);
    }

    private MockHttpServletRequestBuilder bookingRequest(String path) {
        return post(path).header(AUTHORIZATION, user).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId())));
    }

    private double counter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
