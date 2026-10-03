package com.example.seatreservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.exception.IdempotencyConflictException;
import com.example.seatreservation.repository.IdempotencyKeyRepository;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.ShowRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "runPostgresIntegrationTests", matches = "true")
class ReservationIdempotencyIntegrationTest {

    @Autowired
    private ShowService showService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getProperty(
                "integration.jdbc-url", "jdbc:postgresql://localhost:5433/seat_reservation"));
        properties.add("spring.datasource.username", () -> System.getProperty(
                "integration.jdbc-username", "seat_reservation"));
        properties.add("spring.datasource.password", () -> System.getProperty(
                "integration.jdbc-password", "seat_reservation_dev"));
    }

    @Test
    void sequentialRetryReturnsOriginalReservation() {
        Fixture fixture = fixture();
        ReserveSeatsRequest request = request("A1", "A2");

        ReservationResponse original = reservationService.reserve(fixture.showId(), 101L, request);
        ReservationResponse retry = reservationService.reserve(fixture.showId(), 101L, request);

        assertThat(retry).isEqualTo(original);
        assertThat(idempotencyKeyRepository.countByUserIdAndShowIdAndIdempotencyKey(
                101L, fixture.internalShowId(), request.idempotencyKey())).isEqualTo(1);
        assertThat(reservationRepository.countByShowIdAndUserId(fixture.internalShowId(), 101L))
                .isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentSeatsConflicts() {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();
        reservationService.reserve(fixture.showId(), 102L, requestWithKey(key, "A1"));

        assertThatThrownBy(() ->
                reservationService.reserve(fixture.showId(), 102L, requestWithKey(key, "A2")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(reservationRepository.countByShowIdAndUserId(fixture.internalShowId(), 102L))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameKeyRequestsCreateOneReservation() throws Exception {
        Fixture fixture = fixture();
        ReserveSeatsRequest request = request("A1", "A2");
        int requestCount = 8;
        var executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<ReservationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent requests");
                    }
                    return reservationService.reserve(fixture.showId(), 103L, request);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReservationResponse> responses = new ArrayList<>();
            for (var future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).hasSize(requestCount);
            assertThat(responses)
                    .extracting(ReservationResponse::reservationId)
                    .containsOnly(responses.getFirst().reservationId());
            assertThat(idempotencyKeyRepository.countByUserIdAndShowIdAndIdempotencyKey(
                    103L, fixture.internalShowId(), request.idempotencyKey())).isEqualTo(1);
            assertThat(reservationRepository.countByShowIdAndUserId(fixture.internalShowId(), 103L))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentUsersCanUseSameKeyIndependently() {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();

        ReservationResponse first = reservationService.reserve(
                fixture.showId(), 104L, requestWithKey(key, "A1"));
        ReservationResponse second = reservationService.reserve(
                fixture.showId(), 105L, requestWithKey(key, "A2"));

        assertThat(first.reservationId()).isNotEqualTo(second.reservationId());
        assertThat(first.userId()).isEqualTo(104L);
        assertThat(second.userId()).isEqualTo(105L);
        assertThat(idempotencyKeyRepository.countByUserIdAndShowIdAndIdempotencyKey(
                104L, fixture.internalShowId(), key)).isEqualTo(1);
        assertThat(idempotencyKeyRepository.countByUserIdAndShowIdAndIdempotencyKey(
                105L, fixture.internalShowId(), key)).isEqualTo(1);
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        var response = showService.createShow(new CreateShowRequest(
                "idempotency-" + suffix,
                List.of("A1", "A2", "A3"),
                25000L,
                4));
        Long internalShowId = showRepository.findByPublicId(response.showId())
                .orElseThrow()
                .getId();
        return new Fixture(response.showId(), internalShowId);
    }

    private static ReserveSeatsRequest request(String... seats) {
        return requestWithKey(UUID.randomUUID().toString(), seats);
    }

    private static ReserveSeatsRequest requestWithKey(String key, String... seats) {
        return new ReserveSeatsRequest(List.of(seats), key);
    }

    private record Fixture(UUID showId, Long internalShowId) {
    }
}
