package com.example.seatreservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.repository.SeatRepository;

@Component
public class ReservationMetrics {

    private final Counter confirmed;
    private final Counter seatTaken;
    private final Counter perUserLimit;
    private final Counter idempotentReplay;
    private final Counter idempotencyConflict;

    public ReservationMetrics(MeterRegistry meterRegistry, SeatRepository seatRepository) {
        confirmed = Counter.builder("reservation.confirmed")
                .description("Reservations committed successfully")
                .register(meterRegistry);
        seatTaken = declinedCounter(meterRegistry, "seat_taken");
        perUserLimit = declinedCounter(meterRegistry, "per_user_limit");
        idempotentReplay = declinedCounter(meterRegistry, "idempotent_replay");
        idempotencyConflict = declinedCounter(meterRegistry, "idempotency_conflict");
        Gauge.builder("seats.available", seatRepository,
                        repository -> repository.countByStatus(SeatStatus.AVAILABLE))
                .description("Current number of available seats in the database")
                .register(meterRegistry);
    }

    public void recordConfirmedAfterCommit() {
        incrementAfterCommit(confirmed);
    }

    public void recordIdempotentReplayAfterCommit() {
        incrementAfterCommit(idempotentReplay);
    }

    public void recordCommittedIdempotentReplay() {
        idempotentReplay.increment();
    }

    public void recordSeatTaken() {
        seatTaken.increment();
    }

    public void recordPerUserLimit() {
        perUserLimit.increment();
    }

    public void recordIdempotencyConflict() {
        idempotencyConflict.increment();
    }

    private static Counter declinedCounter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder("reservation.declined")
                .description("Reservation requests declined or replayed")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    private static void incrementAfterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Reservation metrics require an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
