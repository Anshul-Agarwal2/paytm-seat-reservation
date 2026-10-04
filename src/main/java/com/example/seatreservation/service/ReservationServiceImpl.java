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
import com.example.seatreservation.metrics.ReservationMetrics;
import com.example.seatreservation.repository.IdempotencyKeyRepository;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.ReservationSeatRepository;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;
import com.example.seatreservation.security.AuthenticatedUser;

@Service
public class ReservationServiceImpl implements ReservationService {

    private final AuthenticatedUser authenticatedUser;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final ReservationTransaction reservationTransaction;
    private final ReservationMetrics reservationMetrics;

    public ReservationServiceImpl(
            AuthenticatedUser authenticatedUser,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            SeatRepository seatRepository,
            ShowRepository showRepository,
            ReservationTransaction reservationTransaction,
            ReservationMetrics reservationMetrics) {
        this.authenticatedUser = authenticatedUser;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.reservationTransaction = reservationTransaction;
        this.reservationMetrics = reservationMetrics;
    }

    @Override
    public ReservationResponse reserve(UUID showPublicId, ReserveSeatsRequest request) {
        String requestHash = requestHash(request.seats());
        try {
            return reservationTransaction.create(showPublicId, request, requestHash);
        } catch (DataIntegrityViolationException exception) {
            return recoverIdempotencyRace(showPublicId, request, requestHash, exception);
        }
    }

    private ReservationResponse recoverIdempotencyRace(
            UUID showPublicId,
            ReserveSeatsRequest request,
            String requestHash,
            DataIntegrityViolationException originalException) {
        long userId = authenticatedUser.userId();
        var show = showRepository.findByPublicId(showPublicId).orElse(null);
        if (show == null) {
            throw originalException;
        }
        var winner = idempotencyKeyRepository.findByUserIdAndShowIdAndIdempotencyKey(
                userId, show.getId(), request.idempotencyKey());
        if (winner.isEmpty()) {
            throw originalException;
        }
        ReservationResponse response = replayOrConflict(winner.get(), requestHash, showPublicId);
        reservationMetrics.recordCommittedIdempotentReplay();
        return response;
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
        Reservation reservation = reservationRepository.findById(idempotencyKey.getReservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record references a missing reservation"));
        List<String> seats = reservationSeatRepository.findSeatNumbersByReservationId(reservation.getId());
        return response(reservation, showPublicId, seats);
    }

    private static String requestHash(List<String> seatNumbers) {
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
