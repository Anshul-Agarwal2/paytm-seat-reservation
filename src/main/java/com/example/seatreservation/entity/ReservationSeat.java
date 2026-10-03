package com.example.seatreservation.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation_seats")
public class ReservationSeat {

    @EmbeddedId
    private ReservationSeatId id;

    protected ReservationSeat() {
    }

    public ReservationSeat(Long reservationId, Long seatId) {
        this.id = new ReservationSeatId(reservationId, seatId);
    }

    public ReservationSeatId getId() {
        return id;
    }
}
