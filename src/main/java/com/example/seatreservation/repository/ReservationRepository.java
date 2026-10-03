package com.example.seatreservation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seatreservation.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findAllByShowIdAndUserIdOrderByCreatedAtDesc(Long showId, Long userId);

    long countByShowIdAndUserId(Long showId, Long userId);

    long countByShowId(Long showId);

    Optional<Reservation> findByPublicId(UUID publicId);
}
