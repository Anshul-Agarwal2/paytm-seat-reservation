package com.example.seatreservation.dto;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateShowRequest(
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> seats,
        @JsonProperty("price_paise") @NotNull @PositiveOrZero Long pricePaise,
        @JsonProperty("per_user_limit") @Positive Integer perUserLimit) {

    @JsonIgnore
    @AssertTrue(message = "seat numbers must be unique")
    public boolean hasUniqueSeatNumbers() {
        return seats == null || new HashSet<>(seats).size() == seats.size();
    }
}
