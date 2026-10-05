package com.example.booking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Two application instances share one Redis, so a limit holds across them. Neither instance keeps any counter in
 * its own memory: the totals below can only come from the shared counter.
 */
@TestPropertySource(properties = {"app.rate-limit.login-per-minute=4", "app.rate-limit.booking-per-minute=3"})
class MultiInstanceRateLimitTest extends ConcurrencySupport {

    private static final String[] SAME_LIMITS = {"--app.rate-limit.login-per-minute=4",
            "--app.rate-limit.booking-per-minute=3"};

    @Test
    void theLoginLimitHoldsAcrossTwoInstancesUnderConcurrentLoad() throws Exception {
        try (ConfigurableApplicationContext second = startSecondInstance(SAME_LIMITS)) {
            int secondPort = Integer.parseInt(second.getEnvironment().getProperty("local.server.port"));
            List<Callable<Reply>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int target = i % 2 == 0 ? port : secondPort;
                attempts.add(() -> login(target));
            }

            List<Reply> replies = runConcurrently(attempts);

            assertThat(count(replies, 401)).as("allowed through to the credential check").isEqualTo(4);
            assertThat(count(replies, 429)).as("rejected by the shared limit").isEqualTo(8);
        }
    }

    @Test
    void theBookingLimitIsPerUserAcrossInstances() throws Exception {
        Event event = saveEvent();
        Seat seat = saveSeat(event, "A1");
        String busy = bearerFor(saveUser("busy@example.com", Role.USER));
        String other = bearerFor(saveUser("other@example.com", Role.USER));
        try (ConfigurableApplicationContext second = startSecondInstance(SAME_LIMITS)) {
            int secondPort = Integer.parseInt(second.getEnvironment().getProperty("local.server.port"));
            List<Callable<Reply>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int target = i % 2 == 0 ? port : secondPort;
                attempts.add(() -> postBooking(target, busy, event, seat, null));
            }

            List<Reply> replies = runConcurrently(attempts);

            assertThat(count(replies, 429)).isEqualTo(5);
            assertThat(count(replies, 201) + count(replies, 409)).as("handled by the booking logic").isEqualTo(3);
            assertThat(postBooking(secondPort, other, event, seat, null).status())
                    .as("another user has their own allowance").isEqualTo(409);
            assertSeatConsistency(seat);
        }
    }

    private Reply login(int targetPort) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"Wrong-password1\"}"))
                .build();
        return execute(request);
    }
}
