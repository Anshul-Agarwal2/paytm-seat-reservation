package com.example.seatreservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.dto.SeatStateResponse;
import com.example.seatreservation.dto.ShowDetailsResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.entity.ReservationStatus;
import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.exception.IdempotencyConflictException;
import com.example.seatreservation.exception.PerUserLimitExceededException;
import com.example.seatreservation.exception.SeatAlreadyTakenException;
import com.example.seatreservation.exception.SeatNotFoundException;
import com.example.seatreservation.exception.ShowNotFoundException;
import com.example.seatreservation.metrics.ReservationMetrics;
import com.example.seatreservation.security.AuthenticatedUser;
import com.example.seatreservation.security.JwtTokenUtility;
import com.example.seatreservation.security.SecurityConfig;
import com.example.seatreservation.service.ReservationService;
import com.example.seatreservation.service.ShowService;

@WebMvcTest(ShowController.class)
@Import({SecurityConfig.class, JwtTokenUtility.class, AuthenticatedUser.class})
@ActiveProfiles("local")
class ShowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShowService showService;

    @MockBean
    private ReservationService reservationService;

    @MockBean
    private ReservationMetrics reservationMetrics;

    @Autowired
    private JwtTokenUtility jwtTokenUtility;

    @Test
    void createsShowAndReturnsCreatedResponse() throws Exception {
        UUID showId = UUID.fromString("09bd753e-0a91-4369-b64a-c756c72db12a");
        when(showService.createShow(any(CreateShowRequest.class))).thenReturn(
                new ShowResponse(showId, "friday-night", 25000L, 4, List.of("A1", "A2")));

        mockMvc.perform(post("/shows")
                        .header("Authorization", adminAuthorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "friday-night",
                                  "seats": ["A1", "A2"],
                                  "price_paise": 25000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.show_id").value(showId.toString()))
                .andExpect(jsonPath("$.name").value("friday-night"))
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.per_user_limit").value(4))
                .andExpect(jsonPath("$.seats[0]").value("A1"))
                .andExpect(jsonPath("$.seats[1]").value("A2"));

        verify(showService).createShow(any(CreateShowRequest.class));
    }

    @Test
    void rejectsDuplicateSeats() throws Exception {
        mockMvc.perform(post("/shows")
                        .header("Authorization", adminAuthorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "friday-night",
                                  "seats": ["A1", "A1"],
                                  "price_paise": 25000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(showService);
    }

    @Test
    void rejectsMissingNameEmptySeatsAndNegativePrice() throws Exception {
        mockMvc.perform(post("/shows")
                        .header("Authorization", adminAuthorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " ",
                                  "seats": [],
                                  "price_paise": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(showService);
    }

    @Test
    void getsShowWithCountsAndSeatStatesWithoutCaching() throws Exception {
        UUID showId = UUID.fromString("09bd753e-0a91-4369-b64a-c756c72db12a");
        when(showService.getShow(showId)).thenReturn(new ShowDetailsResponse(
                showId,
                "friday-night",
                25000L,
                3,
                2,
                0,
                1,
                List.of(
                        new SeatStateResponse("A1", SeatStatus.CONFIRMED),
                        new SeatStateResponse("A2", SeatStatus.AVAILABLE),
                        new SeatStateResponse("A3", SeatStatus.AVAILABLE))));

        mockMvc.perform(get("/shows/{showId}", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_id").value(showId.toString()))
                .andExpect(jsonPath("$.name").value("friday-night"))
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(1))
                .andExpect(jsonPath("$.seats[0].seat").value("A1"))
                .andExpect(jsonPath("$.seats[0].status").value("CONFIRMED"))
                .andExpect(header -> org.assertj.core.api.Assertions.assertThat(
                        header.getResponse().getHeader("Cache-Control")).contains("no-store"));

        verify(showService).getShow(showId);
    }

    @Test
    void returnsNotFoundForUnknownShow() throws Exception {
        UUID showId = UUID.randomUUID();
        when(showService.getShow(showId)).thenThrow(new ShowNotFoundException(showId));

        mockMvc.perform(get("/shows/{showId}", showId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SHOW_NOT_FOUND"));
    }

    @Test
    void showCreationRequiresAdminRole() throws Exception {
        mockMvc.perform(post("/shows")
                        .header("Authorization", "Bearer " + jwtTokenUtility.generateToken(123L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "friday-night",
                                  "seats": ["A1"],
                                  "price_paise": 25000
                                }
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(showService);
    }

    @Test
    void reservationPathsRequireBearerAuthentication() throws Exception {
        mockMvc.perform(get("/reservations/1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/reservations"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/shows/{showId}/reserve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":["A1"],"idempotency_key":"request-1"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/reservations/1")
                        .header("Authorization", "Bearer " + jwtTokenUtility.generateToken(123L, "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void reserveUsesJwtIdentityAndReturnsCreatedResponse() throws Exception {
        UUID showId = UUID.fromString("09bd753e-0a91-4369-b64a-c756c72db12a");
        UUID reservationId = UUID.fromString("e363228d-b06d-41e6-b4c8-9757f5f7c184");
        when(reservationService.reserve(eq(showId), any(ReserveSeatsRequest.class)))
                .thenReturn(new ReservationResponse(
                        reservationId,
                        showId,
                        42L,
                        List.of("A12", "A13"),
                        50000L,
                        ReservationStatus.CONFIRMED));

        mockMvc.perform(post("/shows/{showId}/reserve", showId)
                        .header("Authorization", "Bearer " + jwtTokenUtility.generateToken(42L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "user_id": 999,
                                  "seats": ["A12", "A13"],
                                  "idempotency_key": "unique-request-key"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
                .andExpect(jsonPath("$.show_id").value(showId.toString()))
                .andExpect(jsonPath("$.user_id").value(42))
                .andExpect(jsonPath("$.seats[0]").value("A12"))
                .andExpect(jsonPath("$.amount_paise").value(50000))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(reservationService).reserve(eq(showId), any(ReserveSeatsRequest.class));
    }

    @Test
    void reserveRejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/shows/{showId}/reserve", UUID.randomUUID())
                        .header("Authorization", "Bearer " + jwtTokenUtility.generateToken(42L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seats": ["A12", "A12"],
                                  "idempotency_key": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(reservationService);
    }

    @Test
    void mapsSeatNotFoundToNotFound() throws Exception {
        UUID showId = UUID.randomUUID();
        when(reservationService.reserve(eq(showId), any(ReserveSeatsRequest.class)))
                .thenThrow(new SeatNotFoundException("A12"));

        mockMvc.perform(post("/shows/{showId}/reserve", showId)
                        .header("Authorization", "Bearer " + jwtTokenUtility.generateToken(42L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":["A12"],"idempotency_key":"request-1"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SEAT_NOT_FOUND"));
    }

    @Test
    void mapsReservationConflictsToConflict() throws Exception {
        assertReservationConflict(new SeatAlreadyTakenException("A12"), "SEAT_ALREADY_TAKEN");
        assertReservationConflict(new PerUserLimitExceededException(), "PER_USER_LIMIT_EXCEEDED");
        assertReservationConflict(new IdempotencyConflictException(), "IDEMPOTENCY_CONFLICT");

        verify(reservationMetrics).recordSeatTaken();
        verify(reservationMetrics).recordPerUserLimit();
        verify(reservationMetrics).recordIdempotencyConflict();
    }

    private String adminAuthorization() {
        return "Bearer " + jwtTokenUtility.generateToken(456L, "ADMIN");
    }

    private void assertReservationConflict(RuntimeException exception, String error) throws Exception {
        UUID showId = UUID.randomUUID();
        when(reservationService.reserve(eq(showId), any(ReserveSeatsRequest.class)))
                .thenThrow(exception);

        mockMvc.perform(post("/shows/{showId}/reserve", showId)
                        .header("Authorization", "Bearer " + jwtTokenUtility.generateToken(42L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":["A12"],"idempotency_key":"request-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(error));
    }
}
