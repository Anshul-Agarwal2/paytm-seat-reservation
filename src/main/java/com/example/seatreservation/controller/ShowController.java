package com.example.seatreservation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.dto.ShowDetailsResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.service.ReservationService;
import com.example.seatreservation.service.ShowService;

import jakarta.validation.Valid;
import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;
    private final ReservationService reservationService;

    public ShowController(
            ShowService showService,
            ReservationService reservationService) {
        this.showService = showService;
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(showService.createShow(request));
    }

    @GetMapping("/{showId}")
    public ResponseEntity<ShowDetailsResponse> getShow(@PathVariable UUID showId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(showService.getShow(showId));
    }

    @PostMapping("/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
            @PathVariable UUID showId,
            @Valid @RequestBody ReserveSeatsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reservationService.reserve(showId, request));
    }
}
