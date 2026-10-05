package com.example.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.example.booking.service.HoldExpirySweeper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ReservationHoldApiTest extends IntegrationTestSupport {

    @Autowired
    private HoldExpirySweeper sweeper;

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
    void holdingASeatReturnsHeldReservationWithDeadline() throws Exception {
        String body = hold(alice, event, seat)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.holdId").isNumber())
                .andExpect(jsonPath("$.eventId").value(event.getId()))
                .andExpect(jsonPath("$.seatId").value(seat.getId()))
                .andExpect(jsonPath("$.seatNumber").value("A1"))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andReturn().getResponse().getContentAsString();

        Instant expiresAt = Instant.parse(objectMapper.readTree(body).get("expiresAt").asText());
        assertThat(expiresAt).isBetween(Instant.now().plusSeconds(270), Instant.now().plusSeconds(330));
        assertThat(seatStatus()).isEqualTo("HELD");
        mockMvc.perform(get("/api/v1/events/" + event.getId() + "/seats/available").header(AUTHORIZATION, bob))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(outboxCount("RESERVATION_HELD")).isEqualTo(1);
    }

    @Test
    void heldSeatCannotBeHeldOrBookedByAnotherUser() throws Exception {
        hold(alice, event, seat).andExpect(status().isCreated());

        hold(bob, event, seat).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_BOOKED"));
        book(bob, event, seat).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_BOOKED"));
        assertThat(rowCount("select count(*) from reservation_holds")).isEqualTo(1);
        assertThat(rowCount("select count(*) from bookings")).isZero();
    }

    @Test
    void bookedSeatCannotBeHeld() throws Exception {
        book(alice, event, seat).andExpect(status().isCreated());

        hold(bob, event, seat).andExpect(status().isConflict());
        assertThat(rowCount("select count(*) from reservation_holds")).isZero();
    }

    @Test
    void holdRequestsAreValidatedAndAuthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/holds").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("eventId", event.getId(), "seatId", seat.getId()))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
        mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":-1,\"seatId\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/bookings/holds/1/confirm")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bookings/holds/1/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void seatOfAnotherEventAndUnbookableEventsAreRejected() throws Exception {
        Event other = saveEvent();
        Event cancelled = saveEvent(EventStatus.CANCELLED, Instant.now().plus(Duration.ofDays(5)));
        Seat cancelledSeat = saveSeat(cancelled, "A1");

        hold(alice, other, seat).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        hold(alice, cancelled, cancelledSeat).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
    }

    @Test
    void ownerConfirmsHoldAndGetsBooking() throws Exception {
        long holdId = holdId(alice);

        String body = mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/confirm").header(AUTHORIZATION, alice))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/v1/bookings/\\d+")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatNumber").value("A1"))
                .andReturn().getResponse().getContentAsString();

        long bookingId = objectMapper.readTree(body).get("id").asLong();
        assertThat(seatStatus()).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select status from reservation_holds where id = ?", String.class, holdId))
                .isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("select booking_id from reservation_holds where id = ?", Long.class, holdId))
                .isEqualTo(bookingId);
        assertThat(outboxCount("BOOKING_CONFIRMED")).isEqualTo(1);
        mockMvc.perform(get("/api/v1/bookings/" + bookingId).header(AUTHORIZATION, alice)).andExpect(status().isOk());
    }

    @Test
    void onlyTheOwnerMayConfirmAHold() throws Exception {
        long holdId = holdId(alice);

        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/confirm").header(AUTHORIZATION, bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/confirm").header(AUTHORIZATION, admin))
                .andExpect(status().isForbidden());
        assertThat(seatStatus()).isEqualTo("HELD");
    }

    @Test
    void confirmingTwiceOrUnknownHoldFails() throws Exception {
        long holdId = holdId(alice);
        confirm(alice, holdId).andExpect(status().isCreated());

        confirm(alice, holdId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
        confirm(alice, 9999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        assertThat(rowCount("select count(*) from bookings")).isEqualTo(1);
    }

    @Test
    void expiredHoldCannotBeConfirmed() throws Exception {
        long holdId = holdId(alice);
        expire(holdId);

        confirm(alice, holdId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(rowCount("select count(*) from bookings")).isZero();
    }

    @Test
    void holdOnEventThatWasCancelledCannotBeConfirmed() throws Exception {
        long holdId = holdId(alice);
        jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());

        confirm(alice, holdId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
        assertThat(rowCount("select count(*) from bookings")).isZero();
    }

    @Test
    void ownerCancelsHoldAndSeatIsReleasedButHistoryRemains() throws Exception {
        long holdId = holdId(alice);

        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(seatStatus()).isEqualTo("AVAILABLE");
        assertThat(rowCount("select count(*) from reservation_holds where status = 'CANCELLED'")).isEqualTo(1);
        assertThat(outboxCount("RESERVATION_CANCELLED")).isEqualTo(1);
        hold(bob, event, seat).andExpect(status().isCreated());
    }

    @Test
    void adminMayCancelAnyHoldButOtherUsersMayNot() throws Exception {
        long holdId = holdId(alice);

        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/cancel").header(AUTHORIZATION, bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/cancel").header(AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/cancel").header(AUTHORIZATION, alice))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
    }

    @Test
    void sweeperExpiresLapsedHoldsAndReleasesTheirSeats() throws Exception {
        Seat second = saveSeat(event, "A2");
        long lapsed = holdId(alice);
        hold(bob, event, second).andExpect(status().isCreated());
        expire(lapsed);

        int expired = sweeper.sweepOnce();

        assertThat(expired).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from reservation_holds where id = ?", String.class, lapsed))
                .isEqualTo("EXPIRED");
        assertThat(seatStatus()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, second.getId()))
                .isEqualTo("HELD");
        assertThat(outboxCount("RESERVATION_EXPIRED")).isEqualTo(1);
        assertThat(sweeper.sweepOnce()).isZero();
    }

    @Test
    void sweeperLeavesConvertedHoldsAndTheirBookingsAlone() throws Exception {
        long holdId = holdId(alice);
        confirm(alice, holdId).andExpect(status().isCreated());
        expire(holdId);

        assertThat(sweeper.sweepOnce()).isZero();

        assertThat(seatStatus()).isEqualTo("BOOKED");
        assertThat(rowCount("select count(*) from bookings where status = 'CONFIRMED'")).isEqualTo(1);
        assertThat(outboxCount("RESERVATION_EXPIRED")).isZero();
    }

    @Test
    void lapsedHoldDoesNotBlockANewHoldBeforeTheSweeperRuns() throws Exception {
        long stale = holdId(alice);
        expire(stale);

        hold(bob, event, seat).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select status from reservation_holds where id = ?", String.class, stale))
                .isEqualTo("EXPIRED");
        assertThat(rowCount("select count(*) from reservation_holds where status = 'HELD'")).isEqualTo(1);
        assertThat(outboxCount("RESERVATION_EXPIRED")).isEqualTo(1);
    }

    @Test
    void lapsedHoldDoesNotBlockADirectBooking() throws Exception {
        long stale = holdId(alice);
        expire(stale);

        book(bob, event, seat).andExpect(status().isCreated());

        assertThat(seatStatus()).isEqualTo("BOOKED");
        assertThat(rowCount("select count(*) from reservation_holds where status = 'EXPIRED'")).isEqualTo(1);
    }

    @Test
    void eventWithHoldsCannotBeDeleted() throws Exception {
        hold(alice, event, seat).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/events/" + event.getId()).header(AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_BOOKINGS"));
    }

    private ResultActions hold(String token, Event forEvent, Seat forSeat) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", forEvent.getId(), "seatId", forSeat.getId()))));
    }

    private long holdId(String token) throws Exception {
        String body = hold(token, event, seat).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("holdId").asLong();
    }

    private ResultActions confirm(String token, long holdId) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/holds/" + holdId + "/confirm").header(AUTHORIZATION, token));
    }

    private ResultActions book(String token, Event forEvent, Seat forSeat) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("eventId", forEvent.getId(), "seatId", forSeat.getId()))));
    }

    private void expire(long holdId) {
        jdbc.update("update reservation_holds set expires_at = now() - interval '1 minute' where id = ?", holdId);
    }

    private String seatStatus() {
        return jdbc.queryForObject("select status from seats where id = ?", String.class, seat.getId());
    }

    private int outboxCount(String type) {
        return rowCount("select count(*) from outbox_event where event_type = ?", type);
    }

    private int rowCount(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
