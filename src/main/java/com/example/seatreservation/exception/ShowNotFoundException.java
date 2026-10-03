package com.example.seatreservation.exception;

import java.util.UUID;

public class ShowNotFoundException extends RuntimeException {

    public ShowNotFoundException(UUID showId) {
        super("Show not found: " + showId);
    }
}
