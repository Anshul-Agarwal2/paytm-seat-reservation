package com.example.seatreservation.exception;

public class ReservationNotCancellableException extends RuntimeException {

    public ReservationNotCancellableException() {
        super("Only confirmed reservations can be cancelled");
    }
}
