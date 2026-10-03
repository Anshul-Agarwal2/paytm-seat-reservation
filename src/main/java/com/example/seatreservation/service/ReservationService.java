package com.example.seatreservation.service;

import java.util.UUID;

import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;

public interface ReservationService {

    ReservationResponse reserve(UUID showId, ReserveSeatsRequest request);
}
