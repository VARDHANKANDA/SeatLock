package com.example.booking.controller;

import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.dto.PageResponse;
import com.example.booking.security.AuthenticatedUser;
import com.example.booking.service.BookingResult;
import com.example.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Book a seat",
            description = "Reserves the seat for the authenticated user. Supply an Idempotency-Key header to make "
                    + "retries safe: a repeated request with the same key returns the original booking with 200.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Seat booked"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier request"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or Idempotency-Key"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
            @ApiResponse(responseCode = "404", description = "Seat not found for the event"),
            @ApiResponse(responseCode = "409", description = "Seat already booked or held, or event not open for booking"),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused for a different request"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see the Retry-After header")
    })
    public ResponseEntity<BookingResponse> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateBookingRequest request,
            @Parameter(description = "Client-generated unique key identifying this booking attempt")
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Pattern(regexp = "^[\\x21-\\x7E]{1,255}$", message = "must be 1-255 printable characters without spaces")
            String idempotencyKey) {
        BookingResult result = bookingService.createBooking(user.id(), request, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok(result.booking());
        }
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.booking().id()).toUri()).body(result.booking());
    }

    @GetMapping("/me")
    @Operation(summary = "List the authenticated user's bookings, newest first")
    public PageResponse<BookingResponse> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return bookingService.listForUser(user.id(), page, size);
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Get a booking (owner or ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Booking belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Booking not found")
    })
    public BookingResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long bookingId) {
        return bookingService.get(bookingId, user);
    }

    @PostMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking and release its seat (owner or ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Booking belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Booking not found"),
            @ApiResponse(responseCode = "409", description = "Booking already cancelled")
    })
    public BookingResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long bookingId) {
        return bookingService.cancel(bookingId, user);
    }
}
