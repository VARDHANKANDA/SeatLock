package com.example.booking.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/bookings");

    @Test
    void apiExceptionsKeepTheirStatusAndCode() {
        ResponseEntity<ApiError> response = handler.handleApiException(
                ApiException.conflict(ErrorCode.SEAT_ALREADY_BOOKED, "The requested seat is no longer available."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("SEAT_ALREADY_BOOKED");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/bookings");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void databaseOutageBecomesServiceUnavailableWithoutLeakingDetails() {
        ResponseEntity<ApiError> pool = handler.handleDatabaseUnavailable(
                new DataAccessResourceFailureException("HikariPool-1 - Connection is not available, jdbc:postgresql://db:5432"),
                request);
        ResponseEntity<ApiError> transaction = handler.handleDatabaseUnavailable(
                new CannotCreateTransactionException("Could not open JPA EntityManager"), request);

        for (ResponseEntity<ApiError> response : List.of(pool, transaction)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(response.getBody().message()).doesNotContain("Hikari", "jdbc", "postgresql", "EntityManager");
        }
    }

    @Test
    void lockFailuresAskTheClientToRetry() {
        ResponseEntity<ApiError> response =
                handler.handleLockingFailure(new CannotAcquireLockException("deadlock detected"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(response.getBody().message()).doesNotContain("deadlock");
    }

    @Test
    void lockTimeoutBecomesARetryableServiceUnavailableWithoutLeakingSql() {
        SQLException timeout = new SQLException("ERROR: canceling statement due to lock timeout", "55P03");

        ResponseEntity<ApiError> response = handler.handleLockingFailure(
                new CannotAcquireLockException("could not execute statement", timeout), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_BUSY");
        assertThat(response.getBody().message()).doesNotContain("lock timeout", "statement", "ERROR");
    }

    @Test
    void lockTimeoutIsRecognisedWhenWrappedInSeveralExceptions() {
        SQLException timeout = new SQLException("lock timeout", "55P03");
        RuntimeException wrapped = new RuntimeException("jpa", new IllegalStateException("hibernate", timeout));

        ResponseEntity<ApiError> response = handler.handleLockingFailure(
                new CannotAcquireLockException("outer", wrapped), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void deadlockVictimsRemainConflicts() {
        SQLException deadlock = new SQLException("deadlock detected", "40P01");

        ResponseEntity<ApiError> response = handler.handleLockingFailure(
                new CannotAcquireLockException("could not execute statement", deadlock), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
    }

    @Test
    void constraintViolationsAreReportedAsGenericConflicts() {
        ResponseEntity<ApiError> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_users_email\""),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).doesNotContain("uq_users_email", "duplicate key");
    }

    @Test
    void unexpectedFailuresAreInternalErrorsWithoutInternalDetails() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new IllegalStateException("secret internal detail at com.example.booking.service.X"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("secret", "com.example");
    }
}
