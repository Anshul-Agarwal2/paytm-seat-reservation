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
import com.example.seatreservation.exception.SeatAlreadyTakenException;
import com.example.seatreservation.exception.SeatNotFoundException;
import com.example.seatreservation.exception.ShowNotFoundException;
import com.example.seatreservation.repository.IdempotencyKeyRepository;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.ReservationSeatRepository;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;

@Service
public class ReservationTransaction {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public ReservationTransaction(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            IdempotencyKeyRepository idempotencyKeyRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public ReservationResponse create(
            Long showId,
            UUID showPublicId,
            long userId,
            ReserveSeatsRequest request,
            String requestHash) {
        var show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException(showPublicId));

        var existing = idempotencyKeyRepository.findByUserIdAndShowIdAndIdempotencyKey(
                userId, showId, request.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, showPublicId);
        }

        List<Seat> seats = seatRepository.findAllByShowIdAndSeatNumberIn(showId, request.seats());
        Map<String, Seat> seatsByNumber = seats.stream()
                .collect(Collectors.toMap(Seat::getSeatNumber, seat -> seat));
        for (String seatNumber : request.seats()) {
            Seat seat = seatsByNumber.get(seatNumber);
            if (seat == null) {
                throw new SeatNotFoundException(seatNumber);
            }
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                var winner = idempotencyKeyRepository.findByUserIdAndShowIdAndIdempotencyKey(
                        userId, showId, request.idempotencyKey());
                if (winner.isPresent()) {
                    return replayOrConflict(winner.get(), requestHash, showPublicId);
                }
                throw new SeatAlreadyTakenException(seatNumber);
            }
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

        return ReservationServiceImpl.response(
                reservation,
                showPublicId,
                request.seats());
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
        List<String> originalSeats = seatRepository
                .findAllByReservationIdOrderBySeatNumberAsc(original.getId())
                .stream()
                .map(Seat::getSeatNumber)
                .toList();
        return ReservationServiceImpl.response(original, showPublicId, originalSeats);
    }
}
