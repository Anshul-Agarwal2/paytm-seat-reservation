package com.example.seatreservation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seatreservation.entity.ReservationSeat;
import com.example.seatreservation.entity.ReservationSeatId;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, ReservationSeatId> {

    List<ReservationSeat> findAllByIdReservationId(Long reservationId);
}
