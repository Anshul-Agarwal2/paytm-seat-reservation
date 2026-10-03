package com.example.seatreservation.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.entity.IdempotencyKey;
import com.example.seatreservation.entity.Reservation;
import com.example.seatreservation.exception.IdempotencyConflictException;
import com.example.seatreservation.exception.ShowNotFoundException;
import com.example.seatreservation.repository.IdempotencyKeyRepository;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;

@Service
public class ReservationServiceImpl implements ReservationService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final ReservationTransaction reservationTransaction;

    public ReservationServiceImpl(
            IdempotencyKeyRepository idempotencyKeyRepository,
            ReservationRepository reservationRepository,
            SeatRepository seatRepository,
            ShowRepository showRepository,
            ReservationTransaction reservationTransaction) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.reservationTransaction = reservationTransaction;
    }

    @Override
    public ReservationResponse reserve(UUID showPublicId, long userId, ReserveSeatsRequest request) {
        String requestHash = requestHash(request.seats());
        var show = showRepository.findByPublicId(showPublicId)
                .orElseThrow(() -> new ShowNotFoundException(showPublicId));

        var existing = idempotencyKeyRepository.findByUserIdAndShowIdAndIdempotencyKey(
                userId, show.getId(), request.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        try {
            return reservationTransaction.create(
                    show.getId(), showPublicId, userId, request, requestHash);
        } catch (DataIntegrityViolationException exception) {
            var winner = idempotencyKeyRepository.findByUserIdAndShowIdAndIdempotencyKey(
                    userId, show.getId(), request.idempotencyKey());
            if (winner.isEmpty()) {
                throw exception;
            }
            return replayOrConflict(winner.get(), requestHash);
        }
    }

    private ReservationResponse replayOrConflict(IdempotencyKey idempotencyKey, String requestHash) {
        if (!MessageDigest.isEqual(
                idempotencyKey.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IdempotencyConflictException();
        }
        Reservation reservation = reservationRepository.findById(idempotencyKey.getReservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record references a missing reservation"));
        var show = showRepository.findById(idempotencyKey.getShowId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record references a missing show"));
        List<String> seats = seatRepository.findAllByReservationIdOrderBySeatNumberAsc(reservation.getId())
                .stream().map(seat -> seat.getSeatNumber()).toList();
        return response(reservation, show.getPublicId(), seats);
    }

    static String requestHash(List<String> seatNumbers) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> canonicalSeats = new ArrayList<>(seatNumbers);
            canonicalSeats.sort(String::compareTo);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(canonicalSeats.size()).array());
            for (String seatNumber : canonicalSeats) {
                byte[] bytes = seatNumber.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    static ReservationResponse response(Reservation reservation, UUID showPublicId, List<String> seats) {
        return new ReservationResponse(
                reservation.getPublicId(),
                showPublicId,
                reservation.getUserId(),
                seats.stream().sorted().toList(),
                reservation.getAmountPaise(),
                reservation.getStatus());
    }
}
