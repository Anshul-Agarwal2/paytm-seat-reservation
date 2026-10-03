package com.example.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DevelopmentTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn) {
}
