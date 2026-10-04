package com.example.seatreservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final HealthController healthController = new HealthController(jdbcTemplate);

    @Test
    void livenessDoesNotRequireDatabase() {
        assertThat(healthController.live()).containsEntry("status", "UP");
        verify(jdbcTemplate, never()).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void readinessIsUpWhenDatabaseQuerySucceeds() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        ResponseEntity<?> response = healthController.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("status", "UP"));
    }

    @Test
    void readinessIsUnavailableWhenDatabaseQueryFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        ResponseEntity<?> response = healthController.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("status", "DOWN"));
    }
}
