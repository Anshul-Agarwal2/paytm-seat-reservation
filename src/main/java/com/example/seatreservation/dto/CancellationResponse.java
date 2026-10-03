package com.example.seatreservation.dto;

import java.util.UUID;

import com.example.seatreservation.entity.ReservationStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CancellationResponse(
        @JsonProperty("reservation_id") UUID reservationId,
        ReservationStatus status) {
}
