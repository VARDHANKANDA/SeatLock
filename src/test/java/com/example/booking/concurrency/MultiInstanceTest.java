package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts a second application instance in the same JVM against the same PostgreSQL and Redis and splits the
 * traffic between the two. Correctness must come from the database, not from either instance.
 */
class MultiInstanceTest extends ConcurrencySupport {

    @Test
    void fiftyUsersSplitAcrossTwoInstancesBookOneSeatOnce() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(50);

        try (ConfigurableApplicationContext second = startSecondInstance()) {
            int secondPort = portOf(second);
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                int target = i % 2 == 0 ? port : secondPort;
                tasks.add(() -> postBooking(target, token, event, seat, null));
            }

            List<Reply> replies = runConcurrently(tasks);

            assertThat(count(replies, 201)).isEqualTo(1);
            assertThat(count(replies, 409)).isEqualTo(49);
            assertThat(rowCount("select count(*) from bookings where seat_id = ? and status = 'CONFIRMED'", seat.getId()))
                    .isEqualTo(1);
            assertSeatConsistency(seat);
        }
    }

    @Test
    void holdsAndBookingsSplitAcrossTwoInstancesYieldASingleWinner() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        List<String> tokens = tokensForNewUsers(40);

        try (ConfigurableApplicationContext second = startSecondInstance()) {
            int secondPort = portOf(second);
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                int target = i % 2 == 0 ? port : secondPort;
                tasks.add(i % 4 < 2
                        ? () -> postHold(target, token, event, seat)
                        : () -> postBooking(target, token, event, seat, null));
            }

            List<Reply> replies = runConcurrently(tasks);

            assertThat(count(replies, 201)).isEqualTo(1);
            assertThat(count(replies, 409)).isEqualTo(39);
            assertSeatConsistency(seat);
        }
    }

    @Test
    void eventUpdateOnOneInstanceIsVisibleThroughTheOtherInstancesCache() throws Exception {
        Event event = saveEvent();
        String admin = bearerFor(saveUser("admin@example.com", Role.ADMIN));

        try (ConfigurableApplicationContext second = startSecondInstance()) {
            int secondPort = portOf(second);
            Reply before = execute(request(secondPort, admin, "/api/v1/events/" + event.getId()).GET().build());
            assertThat(before.body()).contains("\"status\":\"SCHEDULED\"");
            assertThat(redis.hasKey("event:" + event.getId())).isTrue();

            assertThat(cancelEvent(admin, event).status()).isEqualTo(200);

            Reply after = execute(request(secondPort, admin, "/api/v1/events/" + event.getId()).GET().build());
            assertThat(after.body()).contains("\"status\":\"CANCELLED\"");
        }
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }
}
