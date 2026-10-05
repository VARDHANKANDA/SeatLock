package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.service.HoldExpirySweeper;
import com.example.booking.service.ReservationHoldService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hold races: many threads, real HTTP, real PostgreSQL. The seat row lock must serialise every transition. */
class HoldConcurrencyTest extends ConcurrencySupport {

    private static final int USERS = 50;

    @Autowired
    private ReservationHoldService holdService;
    @Autowired
    private HoldExpirySweeper sweeper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @RepeatedTest(3)
    void fiftyUsersHoldingOneSeatProduceExactlyOneActiveHold() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(USERS);

        List<Reply> replies = runConcurrently(tokens.stream()
                .<Callable<Reply>>map(token -> () -> postHold(token, event, seat))
                .toList());

        assertThat(count(replies, 201)).isEqualTo(1);
        assertThat(count(replies, 409)).isEqualTo(USERS - 1);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .allSatisfy(reply -> assertThat(reply.body()).contains("SEAT_ALREADY_BOOKED"));
        assertThat(rowCount("select count(*) from reservation_holds where seat_id = ?", seat.getId())).isEqualTo(1);
        assertThat(rowCount("select count(*) from reservation_holds where seat_id = ? and status = 'HELD'", seat.getId()))
                .isEqualTo(1);
        assertSeatConsistency(seat);
    }

    @Test
    void holdsAcrossManySeatsClaimEachSeatExactlyOnce() throws Exception {
        Event event = saveEvent();
        List<Seat> seats = IntStream.range(0, 10).mapToObj(i -> saveSeat(event, "S" + i)).toList();
        List<String> tokens = tokensForNewUsers(100);

        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            Seat seat = seats.get(i % seats.size());
            tasks.add(() -> postHold(token, event, seat));
        }
        List<Reply> replies = runConcurrently(tasks);

        assertThat(count(replies, 201)).isEqualTo(seats.size());
        assertThat(count(replies, 409)).isEqualTo(tokens.size() - seats.size());
        seats.forEach(this::assertSeatConsistency);
    }

    @Test
    void holdsAndDirectBookingsRacingForOneSeatYieldASingleWinner() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(40);

        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            tasks.add(i % 2 == 0 ? () -> postHold(token, event, seat) : () -> postBooking(token, event, seat, null));
        }
        List<Reply> replies = runConcurrently(tasks);

        assertThat(count(replies, 201)).isEqualTo(1);
        assertThat(count(replies, 409)).isEqualTo(tokens.size() - 1);
        assertSeatConsistency(seat);
    }

    @Test
    void concurrentConfirmationsOfOneHoldCreateExactlyOneBooking() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String owner = tokensForNewUsers(1).get(0);
        long holdId = holdId(postHold(owner, event, seat).body());

        List<Reply> replies = runConcurrently(IntStream.range(0, 20)
                .<Callable<Reply>>mapToObj(i -> () -> send(owner, "/api/v1/bookings/holds/" + holdId + "/confirm", "{}"))
                .toList());

        assertThat(count(replies, 201)).isEqualTo(1);
        assertThat(count(replies, 409)).isEqualTo(19);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .allSatisfy(reply -> assertThat(reply.body()).contains("HOLD_NOT_ACTIVE"));
        assertThat(rowCount("select count(*) from bookings")).isEqualTo(1);
        assertThat(rowCount("select count(*) from reservation_holds where status = 'CONVERTED'")).isEqualTo(1);
        assertSeatConsistency(seat);
    }

    @Test
    void concurrentExpiryOfOneLapsedHoldExpiresItExactlyOnce() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String owner = tokensForNewUsers(1).get(0);
        long holdId = holdId(postHold(owner, event, seat).body());
        lapse(holdId);

        List<Reply> replies = runConcurrently(IntStream.range(0, 20)
                .<Callable<Reply>>mapToObj(i -> () -> new Reply(holdService.expire(holdId) ? 200 : 204, ""))
                .toList());

        assertThat(count(replies, 200)).isEqualTo(1);
        assertThat(count(replies, 204)).isEqualTo(19);
        assertThat(rowCount("select count(*) from outbox_event where event_type = 'RESERVATION_EXPIRED'")).isEqualTo(1);
        assertSeatConsistency(seat);
    }

    @Test
    void concurrentSweepsExpireEveryLapsedHoldOnce() throws Exception {
        Event event = saveEvent();
        List<Seat> seats = IntStream.range(0, 15).mapToObj(i -> saveSeat(event, "S" + i)).toList();
        List<String> tokens = tokensForNewUsers(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            lapse(holdId(postHold(tokens.get(i), event, seats.get(i)).body()));
        }

        List<Reply> replies = runConcurrently(IntStream.range(0, 6)
                .<Callable<Reply>>mapToObj(i -> () -> new Reply(200, String.valueOf(sweeper.sweepOnce())))
                .toList());

        int expiredAcrossSweeps = replies.stream().mapToInt(reply -> Integer.parseInt(reply.body())).sum();
        assertThat(expiredAcrossSweeps).isEqualTo(seats.size());
        assertThat(rowCount("select count(*) from outbox_event where event_type = 'RESERVATION_EXPIRED'"))
                .isEqualTo(seats.size());
        seats.forEach(this::assertSeatConsistency);
    }

    @Test
    void confirmationRacingWithExpiryNeverConfirmsALapsedHold() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String owner = tokensForNewUsers(1).get(0);
        long holdId = holdId(postHold(owner, event, seat).body());
        lapse(holdId);

        List<Callable<Reply>> tasks = new ArrayList<>();
        IntStream.range(0, 10).forEach(i ->
                tasks.add(() -> send(owner, "/api/v1/bookings/holds/" + holdId + "/confirm", "{}")));
        IntStream.range(0, 5).forEach(i ->
                tasks.add(() -> new Reply(200, String.valueOf(holdService.expire(holdId)))));
        List<Reply> replies = runConcurrently(tasks);

        assertThat(count(replies, 201)).isZero();
        assertThat(replies).noneMatch(reply -> reply.status() >= 500);
        assertThat(rowCount("select count(*) from bookings")).isZero();
        assertSeatConsistency(seat);
    }

    @Test
    void holdsRacingWithCancellationOfAnExistingBookingStayConsistent() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(21);
        String owner = tokens.get(0);
        long bookingId = bookingId(postBooking(owner, event, seat, null).body());

        List<Callable<Reply>> tasks = new ArrayList<>();
        tasks.add(() -> send(owner, "/api/v1/bookings/" + bookingId + "/cancel", "{}"));
        tokens.stream().skip(1).forEach(token -> tasks.add(() -> postHold(token, event, seat)));
        List<Reply> replies = runConcurrently(tasks);

        assertThat(replies.get(0).status()).isEqualTo(200);
        assertThat(replies).noneMatch(reply -> reply.status() >= 500);
        assertThat(count(replies, 201)).isLessThanOrEqualTo(1);
        assertSeatConsistency(seat);
    }

    @Test
    void holdWaitsForUncommittedEventCancellationAndIsThenRejected() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String token = tokensForNewUsers(1).get(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Reply>> hold = new AtomicReference<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());
                hold.set(executor.submit(() -> postHold(token, event, seat)));
                await().atMost(Duration.ofSeconds(30)).until(this::bookingIsWaitingForEventLock);
            });

            Reply reply = hold.get().get(30, TimeUnit.SECONDS);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.body()).contains("EVENT_NOT_BOOKABLE");
            assertThat(rowCount("select count(*) from reservation_holds")).isZero();
            assertSeatConsistency(seat);
        } finally {
            executor.shutdownNow();
        }
    }

    @RepeatedTest(3)
    void eventCancellationRacingWithHoldsNeitherDeadlocksNorLeavesInconsistentState() throws Exception {
        Event event = saveEvent();
        List<Seat> seats = IntStream.range(0, 30).mapToObj(i -> saveSeat(event, "S" + i)).toList();
        List<String> tokens = tokensForNewUsers(seats.size());
        String admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));

        List<Callable<Reply>> tasks = new ArrayList<>();
        tasks.add(() -> cancelEvent(admin, event));
        for (int i = 0; i < seats.size(); i++) {
            String token = tokens.get(i);
            Seat seat = seats.get(i);
            tasks.add(() -> postHold(token, event, seat));
        }
        List<Reply> replies = runConcurrently(tasks);

        assertThat(replies.get(0).status()).isEqualTo(200);
        List<Reply> holdReplies = replies.subList(1, replies.size());
        assertThat(holdReplies).allMatch(reply -> reply.status() == 201 || reply.status() == 409);
        assertThat(rowCount("select count(*) from reservation_holds where status = 'HELD'"))
                .isEqualTo((int) count(holdReplies, 201));
        seats.forEach(this::assertSeatConsistency);
        assertThat(postHold(tokens.get(0), event, seats.get(0)).status()).isEqualTo(409);
    }

    private void lapse(long holdId) {
        jdbc.update("update reservation_holds set expires_at = now() - interval '1 minute' where id = ?", holdId);
    }
}
