package com.example.seatreservation.dto;

import com.example.seatreservation.entity.SeatStatus;

public record SeatStateResponse(String seat, SeatStatus status) {
}
