package com.example.booking.controller;

import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateHoldRequest;
import com.example.booking.dto.HoldResponse;
import com.example.booking.security.AuthenticatedUser;
import com.example.booking.service.ReservationHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/bookings/holds")
@Tag(name = "Reservation holds")
public class ReservationHoldController {

    private final ReservationHoldService holdService;

    public ReservationHoldController(ReservationHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    @Operation(summary = "Hold a seat temporarily",
            description = "Reserves the seat for the authenticated user until `expiresAt`. The hold must be confirmed "
                    + "before then, otherwise the seat is released automatically.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Seat held"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
            @ApiResponse(responseCode = "404", description = "Event or seat not found"),
            @ApiResponse(responseCode = "409", description = "Seat unavailable or event not open for booking"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see the Retry-After header")
    })
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                               @Valid @RequestBody CreateHoldRequest request) {
        return holdService.createHold(user.id(), request);
    }

    @PostMapping("/{holdId}/confirm")
    @Operation(summary = "Confirm a hold and create the booking (hold owner only)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booking created, hold converted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
            @ApiResponse(responseCode = "403", description = "Hold belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Hold not found"),
            @ApiResponse(responseCode = "409", description = "Hold expired, not active, or event not open for booking")
    })
    public ResponseEntity<BookingResponse> confirm(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable Long holdId) {
        BookingResponse booking = holdService.confirm(holdId, user);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/bookings/{id}").buildAndExpand(booking.id()).toUri()).body(booking);
    }

    @PostMapping("/{holdId}/cancel")
    @Operation(summary = "Cancel an active hold and release the seat (owner or ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hold cancelled"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
            @ApiResponse(responseCode = "403", description = "Hold belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Hold not found"),
            @ApiResponse(responseCode = "409", description = "Hold is no longer active")
    })
    public HoldResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long holdId) {
        return holdService.cancel(holdId, user);
    }
}
