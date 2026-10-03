package com.example.seatreservation.exception;

public class PerUserLimitExceededException extends RuntimeException {

    public PerUserLimitExceededException() {
        super("Per-user reservation limit exceeded");
    }
}
