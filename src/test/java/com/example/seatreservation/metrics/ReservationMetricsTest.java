package com.example.seatreservation.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.repository.SeatRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReservationMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SeatRepository seatRepository = mock(SeatRepository.class);
    private final ReservationMetrics reservationMetrics =
            new ReservationMetrics(meterRegistry, seatRepository);

    @AfterEach
    void cleanUpTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void confirmedCounterIncrementsOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();

        reservationMetrics.recordConfirmedAfterCommit();
        assertThat(meterRegistry.get("reservation.confirmed").counter().count()).isZero();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(meterRegistry.get("reservation.confirmed").counter().count()).isEqualTo(1);
    }

    @Test
    void rolledBackTransactionDoesNotIncrementConfirmedCounter() {
        TransactionSynchronizationManager.initSynchronization();

        reservationMetrics.recordConfirmedAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(meterRegistry.get("reservation.confirmed").counter().count()).isZero();
    }

    @Test
    void idempotentReplayCounterIncrementsAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        reservationMetrics.recordIdempotentReplayAfterCommit();
        assertThat(declinedCount("idempotent_replay")).isZero();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(declinedCount("idempotent_replay")).isEqualTo(1);
    }

    @Test
    void availableSeatGaugeReadsCurrentDatabaseCount() {
        when(seatRepository.countByStatus(SeatStatus.AVAILABLE)).thenReturn(5L, 2L);

        assertThat(meterRegistry.get("seats.available").gauge().value()).isEqualTo(5);
        assertThat(meterRegistry.get("seats.available").gauge().value()).isEqualTo(2);
    }

    @Test
    void declinedCountersUseOnlyBoundedReasonLabels() {
        reservationMetrics.recordSeatTaken();
        reservationMetrics.recordPerUserLimit();
        reservationMetrics.recordIdempotencyConflict();

        assertThat(declinedCount("seat_taken")).isEqualTo(1);
        assertThat(declinedCount("per_user_limit")).isEqualTo(1);
        assertThat(declinedCount("idempotency_conflict")).isEqualTo(1);
        assertThat(meterRegistry.find("reservation.declined").meters()).hasSize(4);
    }

    private double declinedCount(String reason) {
        return meterRegistry.get("reservation.declined")
                .tag("reason", reason)
                .counter()
                .count();
    }
}
