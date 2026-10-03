package com.example.seatreservation.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ShowDetailsResponse(
        @JsonProperty("show_id") UUID showId,
        String name,
        @JsonProperty("price_paise") Long pricePaise,
        @JsonProperty("total_seats") long totalSeats,
        long available,
        long held,
        long confirmed,
        List<SeatStateResponse> seats) {
}
