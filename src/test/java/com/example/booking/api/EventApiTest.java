package com.example.booking.api;

import static org.hamcrest.Matchers.contains;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class EventApiTest extends IntegrationTestSupport {

    private String admin;
    private String user;

    @BeforeEach
    void createAccounts() {
        admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));
        user = bearerFor(saveUser("user@example.com", Role.USER));
    }

    @Test
    void adminCreatesEventAndReadsItBack() throws Exception {
        String location = mockMvc.perform(post("/api/v1/events").header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(eventBody(Duration.ofDays(10)))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.name").value("Jazz Night"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location).header(AUTHORIZATION, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue").value("Blue Hall"));
    }

    @Test
    void regularUserCannotManageEvents() throws Exception {
        Event event = saveEvent();

        mockMvc.perform(post("/api/v1/events").header(AUTHORIZATION, user)
                        .contentType(MediaType.APPLICATION_JSON).content(json(eventBody(Duration.ofDays(10)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(put("/api/v1/events/" + event.getId()).header(AUTHORIZATION, user)
                        .contentType(MediaType.APPLICATION_JSON).content(json(updateBody("CANCELLED"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/events/" + event.getId()).header(AUTHORIZATION, user))
                .andExpect(status().isForbidden());
    }

    @Test
    void eventsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void pastEventDateIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/events").header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(eventBody(Duration.ofDays(-1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'eventDateTime')]").exists());
    }

    @Test
    void invalidEventPayloadReturnsAllViolations() throws Exception {
        mockMvc.perform(post("/api/v1/events").header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(3));
    }

    @Test
    void listsEventsInDateOrderWithPaging() throws Exception {
        saveEvent(EventStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(5)));
        saveEvent(EventStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(2)));
        saveEvent(EventStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(9)));

        mockMvc.perform(get("/api/v1/events?page=0&size=2").header(AUTHORIZATION, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].eventDateTime").exists());
        mockMvc.perform(get("/api/v1/events?size=1000").header(AUTHORIZATION, user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'size')]").exists());
    }

    @Test
    void unknownEventIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/events/9999").header(AUTHORIZATION, user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/events/abc").header(AUTHORIZATION, user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void adminUpdatesAndDeletesEventWithoutBookings() throws Exception {
        Event event = saveEvent();

        mockMvc.perform(put("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(updateBody("CANCELLED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.name").value("Renamed"));
        mockMvc.perform(delete("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventWithBookingsCannotBeDeleted() throws Exception {
        Event event = saveEvent();
        var seat = saveSeat(event, "A1");
        mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_BOOKINGS"));
    }

    @Test
    void adminCreatesSeatsAndAvailabilityListingReflectsStatus() throws Exception {
        Event event = saveEvent();
        String path = "/api/v1/events/" + event.getId() + "/seats";

        mockMvc.perform(post(path).header(AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatNumbers", List.of("A1", "A2", "A3")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].eventId").value(event.getId()));

        jdbc.update("update seats set status = 'BOOKED' where seat_number = 'A2'");

        mockMvc.perform(get(path).header(AUTHORIZATION, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get(path + "/available").header(AUTHORIZATION, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].seatNumber", contains("A1", "A3")));
    }

    @Test
    void seatCreationRejectsDuplicatesAndBadInput() throws Exception {
        Event event = saveEvent();
        saveSeat(event, "A1");
        String path = "/api/v1/events/" + event.getId() + "/seats";

        mockMvc.perform(post(path).header(AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatNumbers", List.of("A1", "A2")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_EXISTS"));
        mockMvc.perform(post(path).header(AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatNumbers", List.of("B1", "B1")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(path).header(AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatNumbers", List.of("bad seat!")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(path).header(AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatNumbers", List.of()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/events/9999/seats").header(AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("seatNumbers", List.of("A1")))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(path).header(AUTHORIZATION, user).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatNumbers", List.of("Z9")))))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> eventBody(Duration fromNow) {
        return Map.of("name", "Jazz Night", "description", "Live music", "venue", "Blue Hall",
                "eventDateTime", Instant.now().plus(fromNow).toString());
    }

    private Map<String, Object> updateBody(String status) {
        return Map.of("name", "Renamed", "venue", "Red Hall", "status", status,
                "eventDateTime", Instant.now().plus(Duration.ofDays(3)).toString());
    }
}
