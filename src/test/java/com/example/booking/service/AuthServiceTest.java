package com.example.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtTokenService tokenService;

    private AuthService service() {
        return new AuthService(userRepository, passwordEncoder, tokenService);
    }

    @Test
    void registersUserWithNormalisedEmailAndHashedPassword() {
        UserResponse response = service().register(new RegisterRequest("  Ada  ", "Ada@Example.COM ", "Secret123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ada@example.com");
        assertThat(saved.getValue().getName()).isEqualTo("Ada");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("Secret123");
        assertThat(passwordEncoder.matches("Secret123", saved.getValue().getPasswordHash())).isTrue();
        assertThat(response.email()).isEqualTo("ada@example.com");
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service().register(new RegisterRequest("Ada", "ada@example.com", "Secret123")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));
    }

    @Test
    void reportsEmailConflictWhenUniqueConstraintFiresOnRace() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> service().register(new RegisterRequest("Ada", "ada@example.com", "Secret123")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));
    }

    @Test
    void loginReturnsTokenForValidCredentials() {
        User user = new User("Ada", "ada@example.com", passwordEncoder.encode("Secret123"), Role.USER);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issue(user)).thenReturn(new JwtTokenService.IssuedToken("jwt-value", 3600));

        AuthResponse response = service().login(new LoginRequest("ADA@example.com", "Secret123"));

        assertThat(response.accessToken()).isEqualTo("jwt-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = new User("Ada", "ada@example.com", passwordEncoder.encode("Secret123"), Role.USER);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().login(new LoginRequest("ada@example.com", "wrong")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void loginForUnknownEmailStillPerformsAPasswordVerification() {
        PasswordEncoder encoder = spy(passwordEncoder);
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        AuthService service = new AuthService(userRepository, encoder, tokenService);

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "Secret123")))
                .isInstanceOf(ApiException.class);

        verify(encoder).matches(eq("Secret123"), anyString());
    }

    @Test
    void loginRejectsUnknownEmailWithSameError() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().login(new LoginRequest("nobody@example.com", "Secret123")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }
}
