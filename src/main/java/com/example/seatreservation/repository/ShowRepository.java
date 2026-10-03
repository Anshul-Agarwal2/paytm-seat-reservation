package com.example.seatreservation.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.seatreservation.entity.Show;

public interface ShowRepository extends JpaRepository<Show, Long> {

    Optional<Show> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Show s where s.publicId = :publicId")
    Optional<Show> findByPublicIdForUpdate(@Param("publicId") UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Show s where s.id = :showId")
    Optional<Show> findByIdForUpdate(@Param("showId") Long showId);
}
