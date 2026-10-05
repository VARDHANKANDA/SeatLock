package com.example.booking.seed;

import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.Role;
import com.example.booking.entity.Seat;
import com.example.booking.entity.User;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.SeatRepository;
import com.example.booking.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates demo accounts, an event and seats for local development. Only active with the {@code dev} profile. */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final List<String> ROWS = List.of("A", "B");
    private static final int SEATS_PER_ROW = 10;

    private final SeedProperties properties;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DevDataSeeder(SeedProperties properties, UserRepository userRepository, EventRepository eventRepository,
                         SeatRepository seatRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createUserIfAbsent("Dev Admin", properties.adminEmail(), properties.adminPassword(), Role.ADMIN);
        createUserIfAbsent("Dev User", properties.userEmail(), properties.userPassword(), Role.USER);
        if (eventRepository.count() == 0) {
            createSampleEvent();
        }
    }

    private void createUserIfAbsent(String name, String email, String password, Role role) {
        if (!userRepository.existsByEmail(email)) {
            userRepository.save(new User(name, email, passwordEncoder.encode(password), role));
            log.info("seed.user.created email={} role={}", email, role);
        }
    }

    private void createSampleEvent() {
        Event event = eventRepository.save(new Event("Sample Concert", "Demo event for local development",
                "Main Hall", clock.instant().plus(Duration.ofDays(30)), EventStatus.SCHEDULED));
        List<Seat> seats = ROWS.stream()
                .flatMap(row -> IntStream.rangeClosed(1, SEATS_PER_ROW).mapToObj(n -> new Seat(event.getId(), row + n)))
                .toList();
        seatRepository.saveAll(seats);
        log.info("seed.event.created eventId={} seats={}", event.getId(), seats.size());
    }
}
