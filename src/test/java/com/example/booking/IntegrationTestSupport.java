package com.example.booking;

import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.SeatRepository;
import com.example.booking.repository.UserRepository;
import com.example.booking.security.JwtTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need the full application against real PostgreSQL and Redis. One container of
 * each is started per test JVM through Testcontainers and shared by all test classes; tables and Redis are
 * emptied before every test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    /** Headroom for the many Spring contexts a full test run keeps cached, each with its own connection pool. */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    protected static final String TEST_PASSWORD = "Passw0rd-test";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected MeterRegistry meterRegistry;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected EventRepository eventRepository;
    @Autowired
    protected SeatRepository seatRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JwtTokenService tokenService;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void cleanState() throws IOException, InterruptedException {
        jdbc.execute("TRUNCATE TABLE notification_log, outbox_event, reservation_holds, idempotency_records, "
                + "bookings, seats, events, users RESTART IDENTITY CASCADE");
        REDIS.execInContainer("redis-cli", "flushall");
    }

    /**
     * Starts another application instance in this JVM against the same PostgreSQL and Redis, to exercise
     * behaviour across instances. The caller closes the returned context.
     */
    protected ConfigurableApplicationContext startSecondInstance(String... extraArguments) {
        // Command-line arguments take precedence over the ${...} placeholders in application.yml.
        String[] arguments = Stream.concat(Stream.of(
                        "--server.port=0",
                        "--spring.jmx.enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379)),
                Stream.of(extraArguments)).toArray(String[]::new);
        return new SpringApplicationBuilder(BookingApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run(arguments);
    }

    protected User saveUser(String email, Role role) {
        return userRepository.save(new User("Test User", email, passwordEncoder.encode(TEST_PASSWORD), role));
    }

    protected String bearerFor(User user) {
        return "Bearer " + tokenService.issue(user).value();
    }

    protected Event saveEvent() {
        return saveEvent(EventStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(30)));
    }

    protected Event saveEvent(EventStatus status, Instant when) {
        return eventRepository.save(new Event("Concert", "Live show", "Main Hall", when, status));
    }

    protected Seat saveSeat(Event event, String number) {
        return seatRepository.save(new Seat(event.getId(), number));
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
