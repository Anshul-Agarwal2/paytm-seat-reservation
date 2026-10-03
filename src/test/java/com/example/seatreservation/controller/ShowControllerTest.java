package com.example.seatreservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.service.ShowService;

@WebMvcTest(ShowController.class)
class ShowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShowService showService;

    @Test
    void createsShowAndReturnsCreatedResponse() throws Exception {
        UUID showId = UUID.fromString("09bd753e-0a91-4369-b64a-c756c72db12a");
        when(showService.createShow(any(CreateShowRequest.class))).thenReturn(
                new ShowResponse(showId, "friday-night", 25000L, 4, List.of("A1", "A2")));

        mockMvc.perform(post("/shows")
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
}
