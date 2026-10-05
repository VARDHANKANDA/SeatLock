package com.example.booking.controller;

import com.example.booking.dto.CreateEventRequest;
import com.example.booking.dto.EventResponse;
import com.example.booking.dto.PageResponse;
import com.example.booking.dto.UpdateEventRequest;
import com.example.booking.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(summary = "Create an event (ADMIN)")
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
        EventResponse created = eventService.create(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri()).body(created);
    }

    @GetMapping
    @Operation(summary = "List events ordered by date")
    public PageResponse<EventResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.list(page, size);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Get an event")
    @ApiResponse(responseCode = "404", description = "Event not found")
    public EventResponse get(@PathVariable Long eventId) {
        return eventService.get(eventId);
    }

    @PutMapping("/{eventId}")
    @Operation(summary = "Update an event (ADMIN)")
    @ApiResponse(responseCode = "404", description = "Event not found")
    public EventResponse update(@PathVariable Long eventId, @Valid @RequestBody UpdateEventRequest request) {
        return eventService.update(eventId, request);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an event that has no bookings (ADMIN)")
    @ApiResponse(responseCode = "409", description = "Event has bookings")
    public void delete(@PathVariable Long eventId) {
        eventService.delete(eventId);
    }
}
