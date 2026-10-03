package com.example.seatreservation.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.seatreservation.dto.CancellationResponse;
import com.example.seatreservation.service.ReservationCancellationService;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationCancellationService cancellationService;

    public ReservationController(ReservationCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<CancellationResponse> cancel(
            @PathVariable UUID reservationId) {
        return ResponseEntity.ok(cancellationService.cancel(reservationId));
    }
}
