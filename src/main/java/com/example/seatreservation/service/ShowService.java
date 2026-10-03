package com.example.seatreservation.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.entity.Seat;
import com.example.seatreservation.entity.Show;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;

@Service
public class ShowService {

    private static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        int perUserLimit = request.perUserLimit() == null
                ? DEFAULT_PER_USER_LIMIT
                : request.perUserLimit();
        Show show = showRepository.save(
                new Show(request.name(), request.pricePaise(), perUserLimit));

        List<Seat> seats = request.seats().stream()
                .map(seatNumber -> new Seat(show.getId(), seatNumber))
                .collect(Collectors.toList());
        List<Seat> savedSeats = seatRepository.saveAll(seats);

        return new ShowResponse(
                show.getPublicId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                savedSeats.stream().map(Seat::getSeatNumber).toList());
    }
}
