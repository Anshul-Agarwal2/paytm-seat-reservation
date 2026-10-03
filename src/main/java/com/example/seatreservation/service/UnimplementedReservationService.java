package com.example.seatreservation.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.exception.ReservationServiceNotImplementedException;

@Service
public class UnimplementedReservationService implements ReservationService {

    @Override
    public ReservationResponse reserve(
            UUID showId,
            long authenticatedUserId,
            ReserveSeatsRequest request) {
        throw new ReservationServiceNotImplementedException();
    }
}
