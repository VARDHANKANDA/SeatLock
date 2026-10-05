package com.example.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.booking.dto.CreateEventRequest;
import com.example.booking.dto.EventResponse;
import com.example.booking.dto.SeatResponse;
import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.entity.SeatStatus;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.repository.BookingRepository;
import com.example.booking.cache.EventCache;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.ReservationHoldRepository;
import com.example.booking.repository.SeatRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventAndSeatServiceTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ReservationHoldRepository holdRepository;
    @Mock
    private EventCache cache;
    @Mock
    private SeatRepository seatRepository;

    @Test
    void newEventsStartScheduled() {
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        EventService service = new EventService(eventRepository, bookingRepository, holdRepository, cache);

        EventResponse response = service.create(
                new CreateEventRequest(" Gala ", "desc", " Hall ", Instant.parse("2031-01-01T00:00:00Z")));

        assertThat(response.status()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(response.name()).isEqualTo("Gala");
        assertThat(response.venue()).isEqualTo("Hall");
    }

    @Test
    void eventWithBookingsCannotBeDeleted() {
        Event event = new Event("Gala", null, "Hall", Instant.now(), EventStatus.SCHEDULED);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(bookingRepository.existsByEventId(1L)).thenReturn(true);
        EventService service = new EventService(eventRepository, bookingRepository, holdRepository, cache);

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EVENT_HAS_BOOKINGS));
        verify(eventRepository, never()).delete(event);
    }

    @Test
    void eventWithHoldsCannotBeDeleted() {
        Event event = new Event("Gala", null, "Hall", Instant.now(), EventStatus.SCHEDULED);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(holdRepository.existsByEventId(1L)).thenReturn(true);
        EventService service = new EventService(eventRepository, bookingRepository, holdRepository, cache);

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EVENT_HAS_BOOKINGS));
        verify(eventRepository, never()).delete(event);
    }

    @Test
    void deletingUnknownEventIsNotFound() {
        when(eventRepository.findById(1L)).thenReturn(Optional.empty());
        EventService service = new EventService(eventRepository, bookingRepository, holdRepository, cache);

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND));
    }

    @Test
    void seatsAreCreatedAvailable() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(seatRepository.findExistingSeatNumbers(1L, List.of("A1", "A2"))).thenReturn(List.of());
        when(seatRepository.saveAllAndFlush(anyList())).thenAnswer(i -> i.getArgument(0));
        SeatService service = new SeatService(seatRepository, eventRepository);

        List<SeatResponse> seats = service.createSeats(1L, List.of("A1", "A2"));

        assertThat(seats).extracting(SeatResponse::seatNumber).containsExactly("A1", "A2");
        assertThat(seats).extracting(SeatResponse::status).containsOnly(SeatStatus.AVAILABLE);
    }

    @Test
    void duplicateSeatNumbersInRequestAreRejected() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        SeatService service = new SeatService(seatRepository, eventRepository);

        assertThatThrownBy(() -> service.createSeats(1L, List.of("A1", "A1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));
        verify(seatRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void existingSeatNumbersAreReportedAsConflict() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(seatRepository.findExistingSeatNumbers(1L, List.of("A1", "A2"))).thenReturn(List.of("A2"));
        SeatService service = new SeatService(seatRepository, eventRepository);

        assertThatThrownBy(() -> service.createSeats(1L, List.of("A1", "A2")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.SEAT_ALREADY_EXISTS);
                    assertThat(e.getMessage()).contains("A2");
                });
    }

    @Test
    void seatsCannotBeAddedToUnknownEvent() {
        when(eventRepository.existsById(1L)).thenReturn(false);
        SeatService service = new SeatService(seatRepository, eventRepository);

        assertThatThrownBy(() -> service.createSeats(1L, List.of("A1"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND));
    }
}
