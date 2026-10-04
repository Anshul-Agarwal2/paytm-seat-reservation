package com.example.seatreservation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.seatreservation.entity.Seat;
import com.example.seatreservation.entity.SeatStatus;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findAllByShowIdOrderBySeatNumberAsc(Long showId);

    long countByStatus(SeatStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Seat s
            where s.showId = :showId and s.seatNumber in :seatNumbers
            order by s.seatNumber asc
            """)
    List<Seat> findAllByShowIdAndSeatNumberInForUpdate(
            @Param("showId") Long showId,
            @Param("seatNumbers") List<String> seatNumbers);

    List<Seat> findAllByShowIdAndStatusOrderBySeatNumberAsc(Long showId, SeatStatus status);

    List<Seat> findAllByReservationIdOrderBySeatNumberAsc(Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Seat s
            where s.reservationId = :reservationId
            order by s.seatNumber asc
            """)
    List<Seat> findAllByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    @Query("""
            select count(s) from Seat s
            join Reservation r on r.id = s.reservationId
            where s.showId = :showId and r.userId = :userId
            """)
    long countReservedSeatsByShowIdAndUserId(
            @Param("showId") Long showId,
            @Param("userId") Long userId);

    Optional<Seat> findByPublicId(UUID publicId);
}
