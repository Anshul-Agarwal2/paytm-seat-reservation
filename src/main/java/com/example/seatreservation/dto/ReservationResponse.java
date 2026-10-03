package com.example.seatreservation.dto;

import java.util.List;
import java.util.UUID;

import com.example.seatreservation.entity.ReservationStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ReservationResponse(
        @JsonProperty("reservation_id") UUID reservationId,
        @JsonProperty("show_id") UUID showId,
        @JsonProperty("user_id") Long userId,
        List<String> seats,
        @JsonProperty("amount_paise") Long amountPaise,
        ReservationStatus status) {
}
