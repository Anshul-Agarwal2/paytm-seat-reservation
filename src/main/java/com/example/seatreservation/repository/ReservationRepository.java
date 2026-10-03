package com.example.seatreservation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.seatreservation.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findAllByShowIdAndUserIdOrderByCreatedAtDesc(Long showId, Long userId);

    long countByShowIdAndUserId(Long showId, Long userId);

    long countByShowId(Long showId);

    Optional<Reservation> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.publicId = :publicId")
    Optional<Reservation> findByPublicIdForUpdate(@Param("publicId") UUID publicId);
}
