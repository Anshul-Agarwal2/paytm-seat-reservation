package com.example.seatreservation.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.seatreservation.dto.CancellationResponse;
import com.example.seatreservation.entity.ReservationStatus;
import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.exception.ReservationNotCancellableException;
import com.example.seatreservation.exception.ReservationNotFoundException;
import com.example.seatreservation.exception.ReservationOwnershipException;
import com.example.seatreservation.logging.ReservationEventLogger;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;
import com.example.seatreservation.security.AuthenticatedUser;

@Service
public class ReservationCancellationService {

    private final AuthenticatedUser authenticatedUser;
    private final ReservationRepository reservationRepository;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationEventLogger reservationEventLogger;

    public ReservationCancellationService(
            AuthenticatedUser authenticatedUser,
            ReservationRepository reservationRepository,
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationEventLogger reservationEventLogger) {
        this.authenticatedUser = authenticatedUser;
        this.reservationRepository = reservationRepository;
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationEventLogger = reservationEventLogger;
    }

    @Transactional
    public CancellationResponse cancel(UUID reservationPublicId) {
        long userId = authenticatedUser.userId();
        var reservation = reservationRepository.findByPublicId(reservationPublicId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationPublicId));

        var show = showRepository.findByIdForUpdate(reservation.getShowId())
                .orElseThrow(() -> new IllegalStateException(
                        "Reservation references a missing show"));
        reservation = reservationRepository.findByPublicIdForUpdate(reservationPublicId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationPublicId));

        if (reservation.getUserId() != userId) {
            throw new ReservationOwnershipException();
        }
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            reservationEventLogger.cancellationReplayed(show.getPublicId(), reservation.getPublicId());
            return new CancellationResponse(reservation.getPublicId(), reservation.getStatus());
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ReservationNotCancellableException();
        }

        var seats = seatRepository.findAllByReservationIdForUpdate(reservation.getId());
        if (seats.isEmpty()) {
            throw new IllegalStateException("Confirmed reservation has no assigned seats");
        }
        // Retain reservation_seats as history; V3 deliberately decouples it from the current
        // seat assignment so a cancelled seat can be reserved again without deleting history.
        for (var seat : seats) {
            if (!reservation.getId().equals(seat.getReservationId())
                    || seat.getStatus() != SeatStatus.CONFIRMED) {
                throw new IllegalStateException(
                        "Reservation seat assignment changed unexpectedly");
            }
            seat.setReservationId(null);
            seat.setHeldUntil(null);
            seat.setStatus(SeatStatus.AVAILABLE);
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        seatRepository.saveAllAndFlush(seats);
        reservationRepository.flush();
        UUID showPublicId = show.getPublicId();
        UUID reservationPublicId = reservation.getPublicId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reservationEventLogger.cancelled(showPublicId, reservationPublicId);
            }
        });
        return new CancellationResponse(reservation.getPublicId(), reservation.getStatus());
    }
}
