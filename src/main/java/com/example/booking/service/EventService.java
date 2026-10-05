package com.example.booking.service;

import com.example.booking.cache.EventCache;
import com.example.booking.dto.CreateEventRequest;
import com.example.booking.dto.EventResponse;
import com.example.booking.dto.PageResponse;
import com.example.booking.dto.UpdateEventRequest;
import com.example.booking.entity.Event;
import com.example.booking.entity.EventStatus;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.ReservationHoldRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event management. Reads go through a Redis cache; writes evict it after commit. Nothing on the booking path
 * reads the cache.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private static final Sort BY_DATE = Sort.by("eventDateTime", "id");
    private static final TypeReference<EventResponse> EVENT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<PageResponse<EventResponse>> PAGE_TYPE = new TypeReference<>() {
    };

    private final EventRepository eventRepository;
    private final BookingRepository bookingRepository;
    private final ReservationHoldRepository holdRepository;
    private final EventCache cache;

    public EventService(EventRepository eventRepository, BookingRepository bookingRepository,
                        ReservationHoldRepository holdRepository, EventCache cache) {
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.holdRepository = holdRepository;
        this.cache = cache;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Event event = eventRepository.save(new Event(request.name().trim(), request.description(),
                request.venue().trim(), request.eventDateTime(), EventStatus.SCHEDULED));
        cache.evictAfterCommit(event.getId());
        log.info("event.created eventId={}", event.getId());
        return EventResponse.from(event);
    }

    public EventResponse get(Long eventId) {
        String key = cache.eventKey(eventId);
        return cache.get(key, EVENT_TYPE).orElseGet(() -> {
            EventResponse response = EventResponse.from(find(eventId));
            cache.put(key, response);
            return response;
        });
    }

    public PageResponse<EventResponse> list(int page, int size) {
        String key = cache.pageKey(page, size);
        return cache.get(key, PAGE_TYPE).orElseGet(() -> {
            PageResponse<EventResponse> response =
                    PageResponse.from(eventRepository.findAll(PageRequest.of(page, size, BY_DATE)), EventResponse::from);
            cache.put(key, response);
            return response;
        });
    }

    @Transactional
    public EventResponse update(Long eventId, UpdateEventRequest request) {
        Event event = find(eventId);
        event.update(request.name().trim(), request.description(), request.venue().trim(),
                request.eventDateTime(), request.status());
        EventResponse response = EventResponse.from(eventRepository.saveAndFlush(event));
        cache.evictAfterCommit(eventId);
        log.info("event.updated eventId={} status={}", eventId, event.getStatus());
        return response;
    }

    /** Events that have ever been booked or held are kept for history; cancel them instead of deleting. */
    @Transactional
    public void delete(Long eventId) {
        Event event = find(eventId);
        if (bookingRepository.existsByEventId(eventId) || holdRepository.existsByEventId(eventId)) {
            throw hasBookings();
        }
        try {
            eventRepository.delete(event);
            eventRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw hasBookings();
        }
        cache.evictAfterCommit(eventId);
        log.info("event.deleted eventId={}", eventId);
    }

    private Event find(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.EVENT_NOT_FOUND, "Event not found."));
    }

    private static ApiException hasBookings() {
        return ApiException.conflict(ErrorCode.EVENT_HAS_BOOKINGS,
                "The event has bookings or holds and cannot be deleted. Set its status to CANCELLED instead.");
    }
}
