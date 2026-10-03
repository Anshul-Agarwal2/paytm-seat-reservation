package com.example.seatreservation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ShowDetailsResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.service.ShowService;

import jakarta.validation.Valid;
import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(showService.createShow(request));
    }

    @GetMapping("/{showId}")
    public ResponseEntity<ShowDetailsResponse> getShow(@PathVariable UUID showId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(showService.getShow(showId));
    }
}
