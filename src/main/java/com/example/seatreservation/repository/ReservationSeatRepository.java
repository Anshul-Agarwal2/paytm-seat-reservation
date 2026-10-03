package com.example.seatreservation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seatreservation.entity.ReservationSeat;
import com.example.seatreservation.entity.ReservationSeatId;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, ReservationSeatId> {

    List<ReservationSeat> findAllByIdReservationId(Long reservationId);

    @Query("""
            select s.seatNumber
            from Seat s, ReservationSeat rs
            where rs.id.reservationId = :reservationId and rs.id.seatId = s.id
            order by s.seatNumber asc
            """)
    List<String> findSeatNumbersByReservationId(@Param("reservationId") Long reservationId);
}
