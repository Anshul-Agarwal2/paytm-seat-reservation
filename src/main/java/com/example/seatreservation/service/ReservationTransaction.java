package com.example.seatreservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.entity.IdempotencyKey;
import com.example.seatreservation.entity.Reservation;
import com.example.seatreservation.entity.ReservationSeat;
import com.example.seatreservation.entity.ReservationStatus;
import com.example.seatreservation.entity.Seat;
import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.exception.IdempotencyConflictException;
import com.example.seatreservation.exception.PerUserLimitExceededException;
import com.example.seatreservation.exception.SeatAlreadyTakenException;
import com.example.seatreservation.exception.SeatNotFoundException;
import com.example.seatreservation.exception.ShowNotFoundException;
import com.example.seatreservation.repository.IdempotencyKeyRepository;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.ReservationSeatRepository;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;
import com.example.seatreservation.security.AuthenticatedUser;

@Service
public class ReservationTransaction {

    private final AuthenticatedUser authenticatedUser;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public ReservationTransaction(
            AuthenticatedUser authenticatedUser,
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            IdempotencyKeyRepository idempotencyKeyRepository) {
        this.authenticatedUser = authenticatedUser;
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public ReservationResponse create(
            UUID showPublicId,
            ReserveSeatsRequest request,
            String requestHash) {
        long userId = authenticatedUser.userId();
        // This row lock serializes reservations for the show until commit. A later transaction
        // therefore counts the earlier transaction's committed seats before checking the limit.
        var show = showRepository.findByPublicIdForUpdate(showPublicId)
                .orElseThrow(() -> new ShowNotFoundException(showPublicId));
        Long showId = show.getId();

        var existing = idempotencyKeyRepository.findByUserIdAndShowIdAndIdempotencyKey(
                userId, showId, request.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, showPublicId);
        }

        List<String> requestedSeats = request.seats().stream().sorted().toList();
        List<Seat> seats = seatRepository.findAllByShowIdAndSeatNumberInForUpdate(
                showId, requestedSeats);
        Map<String, Seat> seatsByNumber = seats.stream()
                .collect(Collectors.toMap(Seat::getSeatNumber, seat -> seat));
        for (String seatNumber : requestedSeats) {
            Seat seat = seatsByNumber.get(seatNumber);
            if (seat == null) {
                throw new SeatNotFoundException(seatNumber);
            }
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new SeatAlreadyTakenException(seatNumber);
            }
        }

        long currentSeatCount = seatRepository.countReservedSeatsByShowIdAndUserId(showId, userId);
        if (currentSeatCount + seats.size() > show.getPerUserLimit()) {
            throw new PerUserLimitExceededException();
        }

        long amountPaise = Math.multiplyExact(show.getPricePaise(), (long) seats.size());
        Reservation reservation = reservationRepository.saveAndFlush(new Reservation(
                showId, userId, amountPaise, ReservationStatus.CONFIRMED));
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.CONFIRMED);
            seat.setReservationId(reservation.getId());
        }
        seatRepository.saveAllAndFlush(seats);
        reservationSeatRepository.saveAllAndFlush(seats.stream()
                .map(seat -> new ReservationSeat(reservation.getId(), seat.getId()))
                .toList());
        idempotencyKeyRepository.saveAndFlush(new IdempotencyKey(
                userId,
                showId,
                request.idempotencyKey(),
                requestHash,
                reservation.getId()));

        return ReservationServiceImpl.response(reservation, showPublicId, requestedSeats);
    }

    private ReservationResponse replayOrConflict(
            IdempotencyKey idempotencyKey,
            String requestHash,
            UUID showPublicId) {
        if (!MessageDigest.isEqual(
                idempotencyKey.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IdempotencyConflictException();
        }
        Reservation original = reservationRepository.findById(idempotencyKey.getReservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record references a missing reservation"));
        List<String> originalSeats = reservationSeatRepository
                .findSeatNumbersByReservationId(original.getId());
        return ReservationServiceImpl.response(original, showPublicId, originalSeats);
    }
}
