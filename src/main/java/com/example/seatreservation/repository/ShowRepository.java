package com.example.seatreservation.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seatreservation.entity.Show;

public interface ShowRepository extends JpaRepository<Show, Long> {

    Optional<Show> findByPublicId(UUID publicId);
}
