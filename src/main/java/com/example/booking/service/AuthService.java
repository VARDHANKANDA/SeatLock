package com.example.booking.service;

import com.example.booking.dto.AuthResponse;
import com.example.booking.dto.LoginRequest;
import com.example.booking.dto.RegisterRequest;
import com.example.booking.dto.UserResponse;
import com.example.booking.entity.Role;
import com.example.booking.entity.User;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.repository.UserRepository;
import com.example.booking.security.JwtTokenService;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    /** Verified against when the email is unknown, so unknown and wrong-password logins cost the same. */
    private final String unknownUserHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.unknownUserHash = passwordEncoder.encode("unknown-user-placeholder");
    }

    /**
     * Not transactional on purpose: BCrypt is CPU-heavy and must not hold a pooled database connection.
     * Each repository call runs in its own short transaction, and the unique index decides racing registrations.
     */
    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }
        User user = new User(request.name().trim(), email, passwordEncoder.encode(request.password()), Role.USER);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        log.info("user.registered userId={}", user.getId());
        return UserResponse.from(user);
    }

    /** Not transactional, for the same reason as {@link #register}. */
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmail(normalize(request.email()));
        String hash = found.map(User::getPasswordHash).orElse(unknownUserHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (found.isEmpty() || !passwordMatches) {
            log.info("user.login.failed");
            throw ApiException.unauthorized(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
        }
        User user = found.get();
        JwtTokenService.IssuedToken token = tokenService.issue(user);
        log.info("user.login.succeeded userId={}", user.getId());
        return AuthResponse.bearer(token.value(), token.expiresInSeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException emailTaken() {
        return ApiException.conflict(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists.");
    }
}
