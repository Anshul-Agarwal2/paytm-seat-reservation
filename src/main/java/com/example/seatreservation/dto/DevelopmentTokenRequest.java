package com.example.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record DevelopmentTokenRequest(
        @JsonProperty("user_id") @NotNull @Positive Long userId,
        @Pattern(regexp = "USER|ADMIN") String role) {
}
