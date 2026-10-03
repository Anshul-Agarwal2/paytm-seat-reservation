package com.example.seatreservation.exception;

public class ReservationOwnershipException extends RuntimeException {

    public ReservationOwnershipException() {
        super("Only the reservation owner can cancel it");
    }
}
