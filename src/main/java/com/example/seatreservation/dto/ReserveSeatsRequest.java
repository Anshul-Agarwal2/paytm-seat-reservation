package com.example.seatreservation.dto;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record ReserveSeatsRequest(
        @NotEmpty List<@NotBlank String> seats,
        @JsonProperty("idempotency_key") @NotBlank String idempotencyKey) {

    @JsonIgnore
    @AssertTrue(message = "seat numbers must be unique")
    public boolean hasUniqueSeatNumbers() {
        return seats == null || new HashSet<>(seats).size() == seats.size();
    }
}
