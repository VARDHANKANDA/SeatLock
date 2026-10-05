package com.example.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

class BookingApiTest extends IntegrationTestSupport {

    private String alice;
    private String bob;
    private String admin;
    private Event event;
    private Seat seat;

    @BeforeEach
    void setUp() {
        alice = bearerFor(saveUser("alice@example.com", Role.USER));
        bob = bearerFor(saveUser("bob@example.com", Role.USER));
        admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));
        event = saveEvent();
        seat = saveSeat(event, "A1");
    }

    @Test
    void bookingASeatReturnsConfirmedBookingAndMarksSeatBooked() throws Exception {
        book(alice, event.getId(), seat.getId(), null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/v1/bookings/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.eventId").value(event.getId()))
                .andExpect(jsonPath("$.seatId").value(seat.getId()))
                .andExpect(jsonPath("$.seatNumber").value("A1"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat.getId()))
                .isEqualTo("BOOKED");
        mockMvc.perform(get("/api/v1/events/" + event.getId() + "/seats/available").header(AUTHORIZATION, bob))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void secondBookingOfSameSeatIsAConflict() throws Exception {
        book(alice, event.getId(), seat.getId(), null).andExpect(status().isCreated());

        book(bob, event.getId(), seat.getId(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_BOOKED"))
                .andExpect(jsonPath("$.message").value("The requested seat is no longer available."))
                .andExpect(jsonPath("$.path").value("/api/v1/bookings"));
    }

    @Test
    void seatCannotBeBookedThroughAnotherEvent() throws Exception {
        Event other = saveEvent();

        book(alice, other.getId(), seat.getId(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from bookings", Integer.class)).isZero();
    }

    @Test
    void unknownSeatIsNotFound() throws Exception {
        book(alice, event.getId(), 9999L, null).andExpect(status().isNotFound());
    }

    @Test
    void cancelledOrStartedEventsAreNotBookable() throws Exception {
        Event cancelled = saveEvent(EventStatus.CANCELLED, Instant.now().plus(Duration.ofDays(5)));
        Event started = saveEvent(EventStatus.SCHEDULED, Instant.now().minus(Duration.ofHours(1)));
        Seat cancelledSeat = saveSeat(cancelled, "A1");
        Seat startedSeat = saveSeat(started, "A1");

        book(alice, cancelled.getId(), cancelledSeat.getId(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
        book(alice, started.getId(), startedSeat.getId(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
    }

    @Test
    void invalidBookingRequestsAreRejectedBeforeBusinessLogic() throws Exception {
        mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
        mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":-1,\"seatId\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"x\",\"seatId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        book(alice, event.getId(), seat.getId(), "bad key with spaces")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'idempotencyKey')]").exists());
    }

    @Test
    void bookingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerAndAdminCanReadBookingButOtherUsersCannot() throws Exception {
        long bookingId = bookAndGetId(alice);

        mockMvc.perform(get("/api/v1/bookings/" + bookingId).header(AUTHORIZATION, alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(bookingId));
        mockMvc.perform(get("/api/v1/bookings/" + bookingId).header(AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/bookings/" + bookingId).header(AUTHORIZATION, bob))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/bookings/9999").header(AUTHORIZATION, alice))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void listsOnlyTheCallersBookings() throws Exception {
        Seat second = saveSeat(event, "A2");
        bookAndGetId(alice);
        book(bob, event.getId(), second.getId(), null).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/bookings/me").header(AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].seatNumber").value("A1"));
    }

    @Test
    void cancellationReleasesSeatAndAllowsRebookingByAnotherUser() throws Exception {
        long bookingId = bookAndGetId(alice);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat.getId()))
                .isEqualTo("AVAILABLE");

        book(bob, event.getId(), seat.getId(), null).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "select count(*) from bookings where seat_id = ? and status = 'CONFIRMED'", Integer.class, seat.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bookings where seat_id = ?", Integer.class, seat.getId()))
                .isEqualTo(2);
    }

    @Test
    void cancellingTwiceOrCancellingSomeoneElsesBookingFails() throws Exception {
        long bookingId = bookAndGetId(alice);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel").header(AUTHORIZATION, bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_ALREADY_CANCELLED"));
    }

    @Test
    void adminCanCancelAnyBooking() throws Exception {
        long bookingId = bookAndGetId(alice);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel").header(AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void repeatedRequestWithSameIdempotencyKeyReturnsOriginalBooking() throws Exception {
        long first = idOf(book(alice, event.getId(), seat.getId(), "retry-key-1").andExpect(status().isCreated()));

        long replay = idOf(book(alice, event.getId(), seat.getId(), "retry-key-1").andExpect(status().isOk()));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from bookings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records", Integer.class)).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReusedForDifferentSeatIsRejected() throws Exception {
        Seat second = saveSeat(event, "A2");
        book(alice, event.getId(), seat.getId(), "retry-key-1").andExpect(status().isCreated());

        book(alice, event.getId(), second.getId(), "retry-key-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void idempotencyKeysAreScopedPerUser() throws Exception {
        Seat second = saveSeat(event, "A2");

        book(alice, event.getId(), seat.getId(), "shared-key").andExpect(status().isCreated());
        book(bob, event.getId(), second.getId(), "shared-key").andExpect(status().isCreated());
    }

    @Test
    void failedBookingDoesNotConsumeIdempotencyKey() throws Exception {
        book(alice, event.getId(), seat.getId(), null).andExpect(status().isCreated());

        book(bob, event.getId(), seat.getId(), "bob-key").andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from idempotency_records", Integer.class)).isZero();
    }

    private ResultActions book(String token, Long eventId, Long seatId, String idempotencyKey) throws Exception {
        var request = post("/api/v1/bookings").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", eventId, "seatId", seatId)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private long bookAndGetId(String token) throws Exception {
        return idOf(book(token, event.getId(), seat.getId(), null).andExpect(status().isCreated()));
    }

    private long idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
