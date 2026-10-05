package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.outbox.OutboxProcessor;
import com.example.booking.service.HoldExpirySweeper;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fires every state-changing operation at once against a small set of seats: holds, direct bookings, hold
 * confirmation and cancellation, booking cancellation, forced hold expiry, sweeps, event updates and outbox
 * processing. Different operations take locks in different code paths, so this is where a lock-ordering
 * mistake would show up as a deadlock (a 5xx) or an inconsistent seat.
 */
class MixedWorkloadStressTest extends ConcurrencySupport {

    private static final int SEATS = 12;
    private static final int OPERATIONS = 150;
    private static final Set<Integer> EXPECTED_STATUSES = Set.of(200, 201, 204, 409);

    private record Handle(long id, int owner) {
    }

    @Autowired
    private HoldExpirySweeper sweeper;
    @Autowired
    private OutboxProcessor outboxProcessor;

    @RepeatedTest(3)
    void everyOperationRacingEveryOtherNeverDeadlocksAndKeepsAllInvariants(RepetitionInfo repetition) throws Exception {
        Random random = new Random(1000L + repetition.getCurrentRepetition());
        Event event = saveEvent();
        List<Seat> seats = IntStream.range(0, SEATS).mapToObj(i -> saveSeat(event, "S" + i)).toList();
        List<String> tokens = tokensForNewUsers(24);
        String admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));
        Queue<Handle> holds = new ConcurrentLinkedQueue<>();
        Queue<Handle> bookings = new ConcurrentLinkedQueue<>();
        seedHoldsAndBookings(event, seats, tokens, holds, bookings);

        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < OPERATIONS; i++) {
            int owner = random.nextInt(tokens.size());
            String token = tokens.get(owner);
            Seat seat = seats.get(random.nextInt(seats.size()));
            int pick = random.nextInt(100);
            if (pick < 28) {
                tasks.add(() -> record(postHold(token, event, seat), owner, holds, "holdId"));
            } else if (pick < 42) {
                tasks.add(() -> record(postBooking(token, event, seat, null), owner, bookings, "id"));
            } else if (pick < 57) {
                tasks.add(() -> confirmSomeHold(holds, tokens, bookings));
            } else if (pick < 67) {
                tasks.add(() -> actOn(holds, tokens, "/api/v1/bookings/holds/%d/cancel"));
            } else if (pick < 77) {
                tasks.add(() -> actOn(bookings, tokens, "/api/v1/bookings/%d/cancel"));
            } else if (pick < 83) {
                tasks.add(() -> new Reply(200, String.valueOf(sweeper.sweepOnce())));
            } else if (pick < 90) {
                tasks.add(this::lapseSomeHolds);
            } else if (pick < 95) {
                tasks.add(() -> updateEvent(admin, event));
            } else {
                tasks.add(() -> new Reply(200, String.valueOf(outboxProcessor.processBatch())));
            }
        }

        List<Reply> replies = runConcurrently(tasks);

        assertThat(replies).as("only success or conflict responses; a 5xx would mean a deadlock or crash")
                .allSatisfy(reply -> assertThat(EXPECTED_STATUSES).contains(reply.status()));
        seats.forEach(this::assertSeatConsistency);
        assertThat(count("select count(*) from seats where status = 'BOOKED'"))
                .isEqualTo(count("select count(*) from bookings where status = 'CONFIRMED'"));
        assertThat(count("select count(*) from seats where status = 'HELD'"))
                .isEqualTo(count("select count(*) from reservation_holds where status = 'HELD'"));
        assertOutboxMatchesState();
    }

    /** Every state change must have produced exactly one outbox event, no matter how the race went. */
    private void assertOutboxMatchesState() {
        assertThat(count("select count(*) from outbox_event where event_type = 'BOOKING_CONFIRMED'"))
                .isEqualTo(count("select count(*) from bookings"));
        assertThat(count("select count(*) from outbox_event where event_type = 'BOOKING_CANCELLED'"))
                .isEqualTo(count("select count(*) from bookings where status = 'CANCELLED'"));
        assertThat(count("select count(*) from outbox_event where event_type = 'RESERVATION_HELD'"))
                .isEqualTo(count("select count(*) from reservation_holds"));
        assertThat(count("select count(*) from outbox_event where event_type = 'RESERVATION_EXPIRED'"))
                .isEqualTo(count("select count(*) from reservation_holds where status = 'EXPIRED'"));
        assertThat(count("select count(*) from outbox_event where event_type = 'RESERVATION_CANCELLED'"))
                .isEqualTo(count("select count(*) from reservation_holds where status = 'CANCELLED'"));
    }

    private void seedHoldsAndBookings(Event event, List<Seat> seats, List<String> tokens,
                                      Queue<Handle> holds, Queue<Handle> bookings) throws Exception {
        for (int i = 0; i < 4; i++) {
            record(postHold(tokens.get(i), event, seats.get(i)), i, holds, "holdId");
            record(postBooking(tokens.get(i + 4), event, seats.get(i + 4), null), i + 4, bookings, "id");
        }
    }

    private Reply record(Reply reply, int owner, Queue<Handle> sink, String idField) throws Exception {
        if (reply.status() == 201) {
            sink.add(new Handle(objectMapper.readTree(reply.body()).get(idField).asLong(), owner));
        }
        return reply;
    }

    private Reply confirmSomeHold(Queue<Handle> holds, List<String> tokens, Queue<Handle> bookings) throws Exception {
        Handle hold = holds.poll();
        if (hold == null) {
            return new Reply(204, "");
        }
        return record(send(tokens.get(hold.owner()), "/api/v1/bookings/holds/" + hold.id() + "/confirm", "{}"),
                hold.owner(), bookings, "id");
    }

    private Reply actOn(Queue<Handle> source, List<String> tokens, String pathTemplate) throws Exception {
        Handle handle = source.poll();
        if (handle == null) {
            return new Reply(204, "");
        }
        return send(tokens.get(handle.owner()), pathTemplate.formatted(handle.id()), "{}");
    }

    private Reply lapseSomeHolds() {
        int lapsed = jdbc.update("update reservation_holds set expires_at = now() - interval '1 second' "
                + "where status = 'HELD' and id % 2 = 0");
        return new Reply(200, String.valueOf(lapsed));
    }

    private Reply updateEvent(String admin, Event event) throws Exception {
        String body = json(Map.of("name", "Concert", "venue", "Main Hall", "status", "SCHEDULED",
                "eventDateTime", event.getEventDateTime().plus(Duration.ofMinutes(1)).toString()));
        return execute(request(admin, "/api/v1/events/" + event.getId())
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private int count(String sql) {
        return rowCount(sql);
    }
}
