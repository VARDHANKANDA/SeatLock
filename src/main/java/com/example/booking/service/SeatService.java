package com.example.booking.service;

import com.example.booking.dto.PageResponse;
import com.example.booking.dto.SeatResponse;
import com.example.booking.entity.Seat;
import com.example.booking.entity.SeatStatus;
import com.example.booking.exception.ApiException;
import com.example.booking.exception.ErrorCode;
import com.example.booking.repository.EventRepository;
import com.example.booking.repository.SeatRepository;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);
    private static final Sort BY_ID = Sort.by("id");

    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    public SeatService(SeatRepository seatRepository, EventRepository eventRepository) {
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public List<SeatResponse> createSeats(Long eventId, List<String> seatNumbers) {
        requireEvent(eventId);
        if (new HashSet<>(seatNumbers).size() != seatNumbers.size()) {
            throw ApiException.badRequest("The request contains duplicate seat numbers.");
        }
        List<String> existing = seatRepository.findExistingSeatNumbers(eventId, seatNumbers);
        if (!existing.isEmpty()) {
            throw ApiException.conflict(ErrorCode.SEAT_ALREADY_EXISTS,
                    "Seats already exist for this event: " + String.join(", ", existing));
        }
        List<Seat> seats = seatNumbers.stream().map(number -> new Seat(eventId, number)).toList();
        try {
            seats = seatRepository.saveAllAndFlush(seats);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(ErrorCode.DATA_CONFLICT, "Seats could not be created due to a conflicting change.");
        }
        log.info("seats.created eventId={} count={}", eventId, seats.size());
        return seats.stream().map(SeatResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<SeatResponse> list(Long eventId, int page, int size) {
        requireEvent(eventId);
        return PageResponse.from(seatRepository.findByEventId(eventId, PageRequest.of(page, size, BY_ID)), SeatResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<SeatResponse> listAvailable(Long eventId, int page, int size) {
        requireEvent(eventId);
        return PageResponse.from(
                seatRepository.findByEventIdAndStatus(eventId, SeatStatus.AVAILABLE, PageRequest.of(page, size, BY_ID)),
                SeatResponse::from);
    }

    private void requireEvent(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw ApiException.notFound(ErrorCode.EVENT_NOT_FOUND, "Event not found.");
        }
    }
}
