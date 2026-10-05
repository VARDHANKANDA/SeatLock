package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
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

/** Booking races: many threads, real HTTP, real PostgreSQL. */
class ConcurrentBookingTest extends ConcurrencySupport {

    private static final int USERS = 50;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @RepeatedTest(3)
    void fiftyUsersRacingForOneSeatProduceExactlyOneConfirmedBooking() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(USERS);

        List<Reply> replies = runConcurrently(tokens.stream()
                .<Callable<Reply>>map(token -> () -> postBooking(token, event, seat, null))
                .toList());

        assertThat(count(replies, 201)).isEqualTo(1);
        assertThat(count(replies, 409)).isEqualTo(USERS - 1);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .allSatisfy(reply -> assertThat(reply.body()).contains("SEAT_ALREADY_BOOKED"));
        assertSeatConsistency(seat);
        assertThat(rowCount("select count(*) from bookings where seat_id = ?", seat.getId())).isEqualTo(1);
        assertThat(rowCount("select count(*) from bookings where seat_id = ? and status = 'CONFIRMED'", seat.getId()))
                .isEqualTo(1);
    }

    @Test
    void contentionAcrossManySeatsBooksEachSeatExactlyOnce() throws Exception {
        Event event = saveEvent();
        List<Seat> seats = IntStream.range(0, 10).mapToObj(i -> saveSeat(event, "S" + i)).toList();
        List<String> tokens = tokensForNewUsers(100);

        List<Callable<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            Seat seat = seats.get(i % seats.size());
            tasks.add(() -> postBooking(token, event, seat, null));
        }
        List<Reply> replies = runConcurrently(tasks);

        assertThat(count(replies, 201)).isEqualTo(seats.size());
        assertThat(count(replies, 409)).isEqualTo(tokens.size() - seats.size());
        seats.forEach(this::assertSeatConsistency);
        assertThat(rowCount("select count(*) from bookings where status = 'CONFIRMED'")).isEqualTo(seats.size());
        assertThat(rowCount("select count(*) from (select seat_id from bookings where status = 'CONFIRMED' "
                + "group by seat_id having count(*) > 1) duplicates")).isZero();
    }

    @Test
    void retriesWithTheSameIdempotencyKeyCreateOneBooking() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String token = tokensForNewUsers(1).get(0);

        List<Reply> replies = runConcurrently(IntStream.range(0, 20)
                .<Callable<Reply>>mapToObj(i -> () -> postBooking(token, event, seat, "same-key"))
                .toList());

        assertThat(count(replies, 201)).isEqualTo(1);
        assertThat(count(replies, 200)).isEqualTo(19);
        assertThat(replies.stream().map(reply -> bookingId(reply.body())).distinct()).hasSize(1);
        assertThat(rowCount("select count(*) from bookings")).isEqualTo(1);
        assertThat(rowCount("select count(*) from idempotency_records")).isEqualTo(1);
    }

    @Test
    void concurrentCancellationsOfOneBookingSucceedExactlyOnce() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String owner = tokensForNewUsers(1).get(0);
        long bookingId = bookingId(postBooking(owner, event, seat, null).body());

        List<Reply> replies = runConcurrently(IntStream.range(0, 20)
                .<Callable<Reply>>mapToObj(i -> () -> send(owner, "/api/v1/bookings/" + bookingId + "/cancel", "{}"))
                .toList());

        assertThat(count(replies, 200)).isEqualTo(1);
        assertThat(count(replies, 409)).isEqualTo(19);
        assertThat(rowCount("select count(*) from bookings where status = 'CONFIRMED'")).isZero();
        assertSeatConsistency(seat);
    }

    @RepeatedTest(3)
    void cancellationRacingWithNewBookingsNeverLeavesInconsistentState() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(21);
        String owner = tokens.get(0);
        long bookingId = bookingId(postBooking(owner, event, seat, null).body());

        List<Callable<Reply>> tasks = new ArrayList<>();
        tasks.add(() -> send(owner, "/api/v1/bookings/" + bookingId + "/cancel", "{}"));
        tokens.stream().skip(1).forEach(token -> tasks.add(() -> postBooking(token, event, seat, null)));
        List<Reply> replies = runConcurrently(tasks);

        assertThat(replies).noneMatch(reply -> reply.status() >= 500);
        assertThat(count(replies, 201)).isLessThanOrEqualTo(1);
        assertSeatConsistency(seat);
    }

    @Test
    void bookingWaitsForUncommittedEventCancellationAndIsThenRejected() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String token = tokensForNewUsers(1).get(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Reply>> booking = new AtomicReference<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("update events set status = 'CANCELLED' where id = ?", event.getId());
                booking.set(executor.submit(() -> postBooking(token, event, seat, null)));
                await().atMost(Duration.ofSeconds(30)).until(this::bookingIsWaitingForEventLock);
            });

            Reply reply = booking.get().get(30, TimeUnit.SECONDS);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.body()).contains("EVENT_NOT_BOOKABLE");
            assertThat(rowCount("select count(*) from bookings")).isZero();
            assertSeatConsistency(seat);
        } finally {
            executor.shutdownNow();
        }
    }

    @RepeatedTest(3)
    void eventCancellationRacingWithBookingsNeitherDeadlocksNorLeavesInconsistentState() throws Exception {
        Event event = saveEvent();
        List<Seat> seats = IntStream.range(0, 30).mapToObj(i -> saveSeat(event, "S" + i)).toList();
        List<String> tokens = tokensForNewUsers(seats.size());
        String admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));

        List<Callable<Reply>> tasks = new ArrayList<>();
        tasks.add(() -> cancelEvent(admin, event));
        for (int i = 0; i < seats.size(); i++) {
            String token = tokens.get(i);
            Seat seat = seats.get(i);
            tasks.add(() -> postBooking(token, event, seat, null));
        }
        List<Reply> replies = runConcurrently(tasks);

        assertThat(replies.get(0).status()).isEqualTo(200);
        List<Reply> bookingReplies = replies.subList(1, replies.size());
        assertThat(bookingReplies).allMatch(reply -> reply.status() == 201 || reply.status() == 409);
        assertThat(rowCount("select count(*) from bookings where status = 'CONFIRMED'"))
                .isEqualTo((int) count(bookingReplies, 201));
        seats.forEach(this::assertSeatConsistency);

        Reply late = postBooking(tokens.get(0), event, seats.get(0), null);
        assertThat(late.status()).isEqualTo(409);
    }

}
