package com.example.booking.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.booking.config.LockProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LockTimeoutsTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void setsATransactionLocalLockTimeoutInMilliseconds() {
        new LockTimeouts(jdbc, new LockProperties(3000)).apply();

        verify(jdbc).queryForObject(eq("select set_config('lock_timeout', ?, true)"), eq(String.class), eq("3000ms"));
    }

    @Test
    void doesNothingWhenTheTimeoutIsDisabled() {
        new LockTimeouts(jdbc, new LockProperties(0)).apply();

        verifyNoInteractions(jdbc);
    }
}
