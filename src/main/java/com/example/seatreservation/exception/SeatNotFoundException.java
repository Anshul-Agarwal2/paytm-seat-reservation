package com.example.seatreservation.exception;

public class SeatNotFoundException extends RuntimeException {

    public SeatNotFoundException(String seatNumber) {
        super("Seat not found: " + seatNumber);
    }
}
