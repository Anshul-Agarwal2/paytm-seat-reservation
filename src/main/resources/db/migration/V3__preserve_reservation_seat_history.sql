ALTER TABLE reservation_seats
    DROP CONSTRAINT fk_reservation_seats_seat_reservation,
    DROP CONSTRAINT uq_reservation_seats_seat_id;

ALTER TABLE reservation_seats
    ADD CONSTRAINT fk_reservation_seats_seat
        FOREIGN KEY (seat_id) REFERENCES seats (id);
