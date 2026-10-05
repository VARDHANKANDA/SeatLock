package com.example.booking.exception;

import com.example.booking.exception.ApiError.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /** PostgreSQL SQLSTATE lock_not_available, raised when {@code lock_timeout} expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final long LOCK_TIMEOUT_RETRY_AFTER_SECONDS = 1;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return respond(ex.getStatus(), ex.getCode().name(), ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, ErrorCode.DATA_CONFLICT.name(),
                "The request conflicts with the current state of the resource.", request.getRequestURI(), null);
    }

    /**
     * A lock wait that exceeded {@code lock_timeout} means the resource stayed busy, not that the request
     * conflicts with its state, so it is a retryable 503. Deadlock victims and other lock failures remain 409.
     * Both exceptions are translated to the same Spring type, so the SQL state decides.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleLockingFailure(PessimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Lock acquisition failed on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        if (isLockTimeout(ex)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(LOCK_TIMEOUT_RETRY_AFTER_SECONDS))
                    .body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE.value(), ErrorCode.RESOURCE_BUSY.name(),
                            "The resource is busy. Please retry shortly.", request.getRequestURI()));
        }
        return respond(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION.name(),
                "The resource is being modified by another request. Please retry.", request.getRequestURI(), null);
    }

    private static boolean isLockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiError> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
        log.error("Database unavailable on {}: {}", request.getRequestURI(), ex.toString());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.name(),
                "The service is temporarily unavailable. Please retry shortly.", request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "An unexpected error occurred.", request.getRequestURI(), null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> violations.add(new FieldViolation(error.getField(), error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> violations.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        return validationFailure(violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                violations.add(new FieldViolation(result.getMethodParameter().getParameterName(), error.getDefaultMessage()))));
        return validationFailure(violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST.name(),
                "The request body is missing, malformed or contains an invalid value.", request, null);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST.name(),
                "Invalid value for parameter '" + ex.getPropertyName() + "'.", request, null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        if (status.is5xxServerError()) {
            log.error("Request failed with {}", status, ex);
        }
        return body(status, status.name(), status.getReasonPhrase(), request, null);
    }

    private ResponseEntity<Object> validationFailure(List<FieldViolation> violations, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(), "Request validation failed.",
                request, violations);
    }

    private ResponseEntity<Object> body(HttpStatus status, String code, String message, WebRequest request,
                                        @Nullable List<FieldViolation> details) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message, path, details));
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message, String path,
                                             @Nullable List<FieldViolation> details) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message, path, details));
    }
}
