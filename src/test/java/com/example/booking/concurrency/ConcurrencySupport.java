package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Helpers for tests that drive real HTTP requests from many threads against the running application. All
 * threads are released together by a latch so their transactions genuinely overlap.
 */
abstract class ConcurrencySupport extends IntegrationTestSupport {

    @LocalServerPort
    protected int port;

    private final HttpClient http = HttpClient.newHttpClient();

    record Reply(int status, String body) {
    }

    /** Asserts the seat state, its bookings and its holds agree, and that no seat is double-claimed. */
    protected void assertSeatConsistency(Seat seat) {
        int confirmed = rowCount("select count(*) from bookings where seat_id = ? and status = 'CONFIRMED'", seat.getId());
        int held = rowCount("select count(*) from reservation_holds where seat_id = ? and status = 'HELD'", seat.getId());
        String seatStatus = jdbc.queryForObject("select status from seats where id = ?", String.class, seat.getId());
        assertThat(confirmed).isLessThanOrEqualTo(1);
        assertThat(held).isLessThanOrEqualTo(1);
        assertThat(confirmed + held).isLessThanOrEqualTo(1);
        assertThat(seatStatus).isEqualTo(confirmed == 1 ? "BOOKED" : held == 1 ? "HELD" : "AVAILABLE");
    }

    protected List<String> tokensForNewUsers(int count) {
        return tokensForNewUsers("racer", count);
    }

    protected List<String> tokensForNewUsers(String prefix, int count) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        List<User> users = IntStream.range(0, count)
                .mapToObj(i -> new User("User " + i, prefix + i + "@example.com", hash, Role.USER))
                .toList();
        return userRepository.saveAll(users).stream().map(this::bearerFor).collect(Collectors.toList());
    }

    protected List<Reply> runConcurrently(List<Callable<Reply>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Reply>> futures = new ArrayList<>();
            for (Callable<Reply> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) {
                replies.add(future.get(60, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            executor.shutdownNow();
        }
    }

    protected Reply postBooking(String bearer, Event event, Seat seat, String idempotencyKey) throws Exception {
        return postBooking(port, bearer, event, seat, idempotencyKey);
    }

    protected Reply postBooking(int targetPort, String bearer, Event event, Seat seat, String idempotencyKey)
            throws Exception {
        String body = json(Map.of("eventId", event.getId(), "seatId", seat.getId()));
        HttpRequest.Builder request = request(targetPort, bearer, "/api/v1/bookings")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return execute(request.build());
    }

    protected Reply postHold(String bearer, Event event, Seat seat) throws Exception {
        return postHold(port, bearer, event, seat);
    }

    protected Reply postHold(int targetPort, String bearer, Event event, Seat seat) throws Exception {
        String body = json(Map.of("eventId", event.getId(), "seatId", seat.getId()));
        return execute(request(targetPort, bearer, "/api/v1/bookings/holds")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    protected Reply send(String bearer, String path, String body) throws Exception {
        return execute(request(port, bearer, path).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    protected HttpRequest.Builder request(String bearer, String path) {
        return request(port, bearer, path);
    }

    protected HttpRequest.Builder request(int targetPort, String bearer, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json");
    }

    protected Reply execute(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    protected long bookingId(String body) {
        try {
            return objectMapper.readTree(body).get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected response body: " + body, e);
        }
    }

    protected long holdId(String body) {
        try {
            return objectMapper.readTree(body).get("holdId").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected response body: " + body, e);
        }
    }

    /** True while some session is blocked waiting for the shared lock that bookings and holds take on an event. */
    protected boolean bookingIsWaitingForEventLock() {
        return rowCount("select count(*) from pg_stat_activity "
                + "where wait_event_type = 'Lock' and query ilike '%from events%for share%'") > 0;
    }

    protected Reply cancelEvent(String bearer, Event event) throws Exception {
        String body = json(Map.of("name", "Concert", "venue", "Main Hall", "status", "CANCELLED",
                "eventDateTime", event.getEventDateTime().toString()));
        return execute(request(bearer, "/api/v1/events/" + event.getId())
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    protected static long count(List<Reply> replies, int status) {
        return replies.stream().filter(reply -> reply.status() == status).count();
    }

    protected int rowCount(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
