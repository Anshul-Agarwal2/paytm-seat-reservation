package com.example.seatreservation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seatreservation.entity.Seat;
import com.example.seatreservation.entity.SeatStatus;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findAllByShowIdOrderBySeatNumberAsc(Long showId);

    List<Seat> findAllByShowIdAndSeatNumberIn(Long showId, List<String> seatNumbers);

    List<Seat> findAllByShowIdAndStatusOrderBySeatNumberAsc(Long showId, SeatStatus status);

    List<Seat> findAllByReservationIdOrderBySeatNumberAsc(Long reservationId);

    Optional<Seat> findByPublicId(UUID publicId);
}
