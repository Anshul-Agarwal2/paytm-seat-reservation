package com.example.seatreservation.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.seatreservation.dto.CancellationResponse;
import com.example.seatreservation.entity.ReservationStatus;
import com.example.seatreservation.exception.ReservationOwnershipException;
import com.example.seatreservation.metrics.ReservationMetrics;
import com.example.seatreservation.security.JwtTokenUtility;
import com.example.seatreservation.security.SecurityConfig;
import com.example.seatreservation.service.ReservationCancellationService;

@WebMvcTest(ReservationController.class)
@Import({SecurityConfig.class, JwtTokenUtility.class})
@ActiveProfiles("local")
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenUtility jwtTokenUtility;

    @MockBean
    private ReservationCancellationService cancellationService;

    @MockBean
    private ReservationMetrics reservationMetrics;

    @Test
    void requiresAuthenticationToCancel() throws Exception {
        mockMvc.perform(post("/reservations/{id}/cancel", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(cancellationService);
    }

    @Test
    void returnsCancelledResponseForAuthenticatedOwner() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(cancellationService.cancel(reservationId))
                .thenReturn(new CancellationResponse(reservationId, ReservationStatus.CANCELLED));

        mockMvc.perform(post("/reservations/{id}/cancel", reservationId)
                        .header("Authorization", userAuthorization(123L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(cancellationService).cancel(reservationId);
    }

    @Test
    void mapsNonOwnerToForbidden() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(cancellationService.cancel(reservationId)).thenThrow(new ReservationOwnershipException());

        mockMvc.perform(post("/reservations/{id}/cancel", reservationId)
                        .header("Authorization", userAuthorization(456L))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("RESERVATION_FORBIDDEN"));
    }

    private String userAuthorization(long userId) {
        return "Bearer " + jwtTokenUtility.generateToken(userId, "USER");
    }
}
