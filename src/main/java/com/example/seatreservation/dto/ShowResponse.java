package com.example.seatreservation.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ShowResponse(
        @JsonProperty("show_id") UUID showId,
        String name,
        @JsonProperty("price_paise") Long pricePaise,
        @JsonProperty("per_user_limit") Integer perUserLimit,
        List<String> seats) {
}
