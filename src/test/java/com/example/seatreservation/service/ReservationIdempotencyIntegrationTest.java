package com.example.seatreservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.CancellationResponse;
import com.example.seatreservation.dto.ReservationResponse;
import com.example.seatreservation.dto.ReserveSeatsRequest;
import com.example.seatreservation.exception.IdempotencyConflictException;
import com.example.seatreservation.exception.PerUserLimitExceededException;
import com.example.seatreservation.exception.ReservationOwnershipException;
import com.example.seatreservation.exception.SeatAlreadyTakenException;
import com.example.seatreservation.entity.ReservationStatus;
import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.repository.IdempotencyKeyRepository;
import com.example.seatreservation.repository.ReservationRepository;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;
import com.example.seatreservation.security.AuthenticatedUser;
import com.example.seatreservation.security.JwtTokenUtility;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "runPostgresIntegrationTests", matches = "true")
class ReservationIdempotencyIntegrationTest {

    @Autowired
    private ShowService showService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationCancellationService cancellationService;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenUtility jwtTokenUtility;

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

        ReservationResponse original = reserveAs(101L, fixture.showId(), request);
        ReservationResponse retry = reserveAs(101L, fixture.showId(), request);

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
        reserveAs(102L, fixture.showId(), requestWithKey(key, "A1"));

        assertThatThrownBy(() ->
                reserveAs(102L, fixture.showId(), requestWithKey(key, "A2")))
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
                    return reserveAs(103L, fixture.showId(), request);
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

        ReservationResponse first = reserveAs(104L, fixture.showId(), requestWithKey(key, "A1"));
        ReservationResponse second = reserveAs(105L, fixture.showId(), requestWithKey(key, "A2"));

        assertThat(first.reservationId()).isNotEqualTo(second.reservationId());
        assertThat(first.userId()).isEqualTo(104L);
        assertThat(second.userId()).isEqualTo(105L);
        assertThat(idempotencyKeyRepository.countByUserIdAndShowIdAndIdempotencyKey(
                104L, fixture.internalShowId(), key)).isEqualTo(1);
        assertThat(idempotencyKeyRepository.countByUserIdAndShowIdAndIdempotencyKey(
                105L, fixture.internalShowId(), key)).isEqualTo(1);
    }

    @Test
    void oneOfOneHundredConcurrentRequestsCanReserveOneSeat() throws Exception {
        Fixture fixture = fixture(100, List.of("A12"));
        List<ReserveSeatsRequest> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            requests.add(request("A12"));
        }

        List<Outcome> outcomes = concurrently(fixture.showId(), requests, 200L);

        assertOneSuccessAndConflicts(outcomes, SeatAlreadyTakenException.class);
        assertThat(reservationRepository.countByShowId(fixture.internalShowId())).isEqualTo(1);
    }

    @Test
    void oneOfOneHundredConcurrentMultiSeatRequestsCanReserveAllSeats() throws Exception {
        Fixture fixture = fixture(100, List.of("A1", "A2"));
        List<ReserveSeatsRequest> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            requests.add(request("A1", "A2"));
        }

        List<Outcome> outcomes = concurrently(fixture.showId(), requests, 300L);

        assertOneSuccessAndConflicts(outcomes, SeatAlreadyTakenException.class);
        assertThat(reservationRepository.countByShowId(fixture.internalShowId())).isEqualTo(1);
    }

    @Test
    void reversedSeatInputOrderDoesNotDeadlock() throws Exception {
        Fixture fixture = fixture(2, List.of("A1", "A2"));
        List<Outcome> outcomes = concurrently(
                fixture.showId(),
                List.of(request("A1", "A2"), request("A2", "A1")),
                400L);

        assertOneSuccessAndConflicts(outcomes, SeatAlreadyTakenException.class);
    }

    @Test
    void concurrentRequestsForSameUserCannotExceedPerUserLimit() throws Exception {
        Fixture fixture = fixture(1, List.of("A1", "A2"));
        List<Outcome> outcomes = concurrently(
                fixture.showId(),
                List.of(request("A1"), request("A2")),
                500L,
                true);

        assertOneSuccessAndConflicts(outcomes, PerUserLimitExceededException.class);
        assertThat(reservationRepository.countByShowIdAndUserId(fixture.internalShowId(), 500L))
                .isEqualTo(1);
    }

    @Test
    void oneOfTwoUsersCompetingForSameSeatsSucceeds() throws Exception {
        Fixture fixture = fixture(2, List.of("A1", "A2"));
        List<Outcome> outcomes = concurrently(
                fixture.showId(),
                List.of(request("A1", "A2"), request("A1", "A2")),
                600L);

        assertOneSuccessAndConflicts(outcomes, SeatAlreadyTakenException.class);
    }

    @Test
    void unavailableSeatRollsBackOtherRequestedSeats() {
        Fixture fixture = fixture(4, List.of("A1", "A2"));
        reserveAs(800L, fixture.showId(), request("A2"));

        assertThatThrownBy(() ->
                reserveAs(801L, fixture.showId(), request("A1", "A2")))
                .isInstanceOf(SeatAlreadyTakenException.class);

        var show = showService.getShow(fixture.showId());
        assertThat(show.available()).isEqualTo(1);
        assertThat(show.confirmed()).isEqualTo(1);
        assertThat(show.seats())
                .filteredOn(seat -> seat.seat().equals("A1"))
                .singleElement()
                .extracting(seat -> seat.status())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void oneHundredConcurrentRequestsFromSameUserAreSerializedAgainstLimit() throws Exception {
        List<String> seatNumbers = new ArrayList<>();
        List<ReserveSeatsRequest> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String seat = "S" + i;
            seatNumbers.add(seat);
            requests.add(request(seat));
        }
        Fixture fixture = fixture(100, seatNumbers);

        List<Outcome> outcomes = concurrently(fixture.showId(), requests, 700L, true);

        assertThat(outcomes).hasSize(100);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(reservationRepository.countByShowIdAndUserId(fixture.internalShowId(), 700L))
                .isEqualTo(100);
    }

    @Test
    void concurrentHttpRequestsEnforceDefaultFourSeatLimitWithoutServerErrors() throws Exception {
        List<String> seatNumbers = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            seatNumbers.add("L" + i);
        }
        Fixture fixture = fixture(4, seatNumbers);
        long userId = 900L;
        String token = jwtTokenUtility.generateToken(userId, "USER");
        int requestCount = 15;

        var executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                String seatNumber = seatNumbers.get(i);
                String key = UUID.randomUUID().toString();
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent HTTP requests");
                    }
                    MvcResult result = mockMvc.perform(post("/shows/{showId}/reserve", fixture.showId())
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"seats":["%s"],"idempotency_key":"%s"}
                                            """.formatted(seatNumber, key)))
                            .andReturn();
                    return result.getResponse().getStatus();
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }

            assertThat(statuses).hasSize(requestCount);
            assertThat(statuses.stream().filter(status -> status == 201)).hasSize(4);
            assertThat(statuses.stream().filter(status -> status == 409)).hasSize(requestCount - 4);
            assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(201, 409));
            assertThat(seatRepository.countReservedSeatsByShowIdAndUserId(
                    fixture.internalShowId(), userId)).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationReleasesSeatsAndRepeatedCancellationIsIdempotent() {
        Fixture fixture = fixture(4, List.of("A1", "A2"));
        ReservationResponse reservation = reserveAs(901L, fixture.showId(), request("A1", "A2"));

        CancellationResponse first = cancelAs(901L, reservation.reservationId());
        CancellationResponse retry = cancelAs(901L, reservation.reservationId());

        assertThat(first)
                .isEqualTo(new CancellationResponse(reservation.reservationId(), ReservationStatus.CANCELLED));
        assertThat(retry).isEqualTo(first);
        var show = showService.getShow(fixture.showId());
        assertThat(show.available()).isEqualTo(2);
        assertThat(show.confirmed()).isZero();
    }

    @Test
    void nonOwnerCannotCancelReservationOrReleaseItsSeats() {
        Fixture fixture = fixture(4, List.of("A1"));
        ReservationResponse reservation = reserveAs(902L, fixture.showId(), request("A1"));

        assertThatThrownBy(() -> cancelAs(903L, reservation.reservationId()))
                .isInstanceOf(ReservationOwnershipException.class);

        var show = showService.getShow(fixture.showId());
        assertThat(show.confirmed()).isEqualTo(1);
        assertThat(show.available()).isZero();
    }

    @Test
    void concurrentCancellationAndReservationNeverReassignsOldReservationSeat() throws Exception {
        Fixture fixture = fixture(4, List.of("A1"));
        ReservationResponse original = reserveAs(904L, fixture.showId(), request("A1"));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CancellationResponse> cancellation = executor.submit(() -> {
                ready.countDown();
                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start cancellation");
                }
                return cancelAs(904L, original.reservationId());
            });
            Future<ReservationResponse> newReservation = executor.submit(() -> {
                ready.countDown();
                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start reservation");
                }
                try {
                    return reserveAs(905L, fixture.showId(), request("A1"));
                } catch (SeatAlreadyTakenException exception) {
                    return null;
                }
            });

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(cancellation.get(60, TimeUnit.SECONDS).status())
                    .isEqualTo(ReservationStatus.CANCELLED);
            ReservationResponse replacement = newReservation.get(60, TimeUnit.SECONDS);

            var seat = seatRepository.findAllByShowIdOrderBySeatNumberAsc(fixture.internalShowId())
                    .getFirst();
            assertThat(reservationRepository.findByPublicId(original.reservationId())
                    .orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            if (replacement == null) {
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
                assertThat(seat.getReservationId()).isNull();
            } else {
                assertThat(replacement.reservationId()).isNotEqualTo(original.reservationId());
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.CONFIRMED);
                assertThat(seat.getReservationId()).isNotEqualTo(
                        reservationRepository.findByPublicId(original.reservationId())
                                .orElseThrow().getId());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture() {
        return fixture(4, List.of("A1", "A2", "A3"));
    }

    private Fixture fixture(int perUserLimit, List<String> seats) {
        String suffix = UUID.randomUUID().toString();
        var response = showService.createShow(new CreateShowRequest(
                "idempotency-" + suffix,
                seats,
                25000L,
                perUserLimit));
        Long internalShowId = showRepository.findByPublicId(response.showId())
                .orElseThrow()
                .getId();
        return new Fixture(response.showId(), internalShowId);
    }

    private List<Outcome> concurrently(
            UUID showId,
            List<ReserveSeatsRequest> requests,
            long firstUserId) throws Exception {
        return concurrently(showId, requests, firstUserId, false);
    }

    private List<Outcome> concurrently(
            UUID showId,
            List<ReserveSeatsRequest> requests,
            long firstUserId,
            boolean sameUser) throws Exception {
        var executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                long userId = sameUser ? firstUserId : firstUserId + i;
                ReserveSeatsRequest request = requests.get(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent requests");
                    }
                    try {
                        return new Outcome(reserveAs(userId, showId, request), null);
                    } catch (SeatAlreadyTakenException | PerUserLimitExceededException exception) {
                        return new Outcome(null, exception);
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertOneSuccessAndConflicts(
            List<Outcome> outcomes,
            Class<? extends RuntimeException> conflictType) {
        assertThat(outcomes).hasSizeGreaterThan(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.response() != null)).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null))
                .hasSize(outcomes.size() - 1)
                .allSatisfy(outcome -> assertThat(outcome.failure()).isInstanceOf(conflictType));
    }

    private static ReserveSeatsRequest request(String... seats) {
        return requestWithKey(UUID.randomUUID().toString(), seats);
    }

    private static ReserveSeatsRequest requestWithKey(String key, String... seats) {
        return new ReserveSeatsRequest(List.of(seats), key);
    }

    private ReservationResponse reserveAs(
            long userId,
            UUID showId,
            ReserveSeatsRequest request) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser.Principal(userId),
                "integration-test",
                List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return reservationService.reserve(showId, request);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private CancellationResponse cancelAs(long userId, UUID reservationId) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser.Principal(userId),
                "integration-test",
                List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return cancellationService.cancel(reservationId);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private record Fixture(UUID showId, Long internalShowId) {
    }

    private record Outcome(ReservationResponse response, RuntimeException failure) {
    }
}
