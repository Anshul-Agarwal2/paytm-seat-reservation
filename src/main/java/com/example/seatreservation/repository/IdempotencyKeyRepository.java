package com.example.seatreservation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seatreservation.entity.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndShowIdAndIdempotencyKey(
            Long userId,
            Long showId,
            String idempotencyKey);
}
