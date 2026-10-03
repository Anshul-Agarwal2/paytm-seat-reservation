package com.example.seatreservation.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ReservationSeatId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    protected ReservationSeatId() {
    }

    public ReservationSeatId(Long reservationId, Long seatId) {
        this.reservationId = reservationId;
        this.seatId = seatId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getSeatId() {
        return seatId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReservationSeatId that)) {
            return false;
        }
        return Objects.equals(reservationId, that.reservationId)
                && Objects.equals(seatId, that.seatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationId, seatId);
    }
}
