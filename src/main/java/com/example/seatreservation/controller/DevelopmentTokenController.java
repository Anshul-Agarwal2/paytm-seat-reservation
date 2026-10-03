package com.example.seatreservation.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.seatreservation.dto.DevelopmentTokenRequest;
import com.example.seatreservation.dto.DevelopmentTokenResponse;
import com.example.seatreservation.security.JwtTokenUtility;

import jakarta.validation.Valid;

@Profile("local")
@RestController
@RequestMapping("/dev/tokens")
public class DevelopmentTokenController {

    private final JwtTokenUtility jwtTokenUtility;

    public DevelopmentTokenController(JwtTokenUtility jwtTokenUtility) {
        this.jwtTokenUtility = jwtTokenUtility;
    }

    @PostMapping
    public ResponseEntity<DevelopmentTokenResponse> createToken(
            @Valid @RequestBody DevelopmentTokenRequest request) {
        String role = request.role() == null ? "USER" : request.role();
        return ResponseEntity.ok(new DevelopmentTokenResponse(
                jwtTokenUtility.generateToken(request.userId(), role),
                "Bearer",
                JwtTokenUtility.TOKEN_TTL_SECONDS));
    }
}
