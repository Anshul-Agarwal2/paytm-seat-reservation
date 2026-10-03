package com.example.seatreservation.exception;

public class ReservationServiceNotImplementedException extends RuntimeException {

    public ReservationServiceNotImplementedException() {
        super("Reservation processing has not been implemented yet");
    }
}
