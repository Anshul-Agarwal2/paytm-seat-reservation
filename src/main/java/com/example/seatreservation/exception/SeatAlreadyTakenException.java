package com.example.seatreservation.exception;

public class SeatAlreadyTakenException extends RuntimeException {

    public SeatAlreadyTakenException(String seatNumber) {
        super("Seat is already taken: " + seatNumber);
    }
}
