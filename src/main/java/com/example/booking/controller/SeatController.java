package com.example.booking.controller;

import com.example.booking.dto.CreateSeatsRequest;
import com.example.booking.dto.PageResponse;
import com.example.booking.dto.SeatResponse;
import com.example.booking.service.SeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events/{eventId}/seats")
@Tag(name = "Seats")
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create seats for an event (ADMIN)")
    @ApiResponse(responseCode = "409", description = "A seat number already exists for the event")
    public List<SeatResponse> create(@PathVariable Long eventId, @Valid @RequestBody CreateSeatsRequest request) {
        return seatService.createSeats(eventId, request.seatNumbers());
    }

    @GetMapping
    @Operation(summary = "List all seats of an event")
    public PageResponse<SeatResponse> list(@PathVariable Long eventId,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return seatService.list(eventId, page, size);
    }

    @GetMapping("/available")
    @Operation(summary = "List seats of an event that can currently be booked")
    public PageResponse<SeatResponse> listAvailable(@PathVariable Long eventId,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return seatService.listAvailable(eventId, page, size);
    }
}
