package com.example.seatreservation.logging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class ReservationEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationEventLogger.class);

    public void confirmed(UUID showId, UUID reservationId) {
        logReservationEvent("reservation_confirmed", showId, reservationId);
    }

    public void cancelled(UUID showId, UUID reservationId) {
        logReservationEvent("reservation_cancelled", showId, reservationId);
    }

    public void cancellationReplayed(UUID showId, UUID reservationId) {
        logReservationEvent("reservation_cancellation_replayed", showId, reservationId);
    }

    public void replayed(UUID showId, UUID reservationId) {
        logReservationEvent("reservation_idempotent_replay", showId, reservationId);
    }

    public void declined(UUID showId, String reason) {
        MDC.put("show_id", showId.toString());
        MDC.put("decline_reason", reason);
        try {
            LOGGER.info("reservation_declined");
        } finally {
            MDC.remove("show_id");
            MDC.remove("decline_reason");
        }
    }

    private static void logReservationEvent(String event, UUID showId, UUID reservationId) {
        MDC.put("show_id", showId.toString());
        MDC.put("reservation_id", reservationId.toString());
        try {
            LOGGER.info(event);
        } finally {
            MDC.remove("show_id");
            MDC.remove("reservation_id");
        }
    }
}
