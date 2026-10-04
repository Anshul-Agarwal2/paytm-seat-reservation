# Seat Reservation

## Local PostgreSQL development

Prerequisites: Docker Compose, Java 21 or later, and Maven.

1. Create your local Compose environment file and start PostgreSQL:

   ```powershell
   Copy-Item .env.example .env
   docker compose up -d postgres
   ```

   `.env.example` contains local-only development values. Change them if needed; never use
   those values in production. Compose persists database files in the `postgres_data` volume.

2. Set the same database values for the Spring Boot process and run the application:

   ```powershell
   $env:POSTGRES_DB = "seat_reservation"
   $env:POSTGRES_USER = "seat_reservation"
   $env:POSTGRES_PASSWORD = "seat_reservation_dev"
   $env:POSTGRES_PORT = "5433"
   mvn spring-boot:run "-Dspring-boot.run.profiles=local"
   ```

   The `local` profile connects to PostgreSQL on `localhost:5433` by default. Set
   `POSTGRES_PORT=5432` in `.env` and in the application environment if you prefer the standard port.
   `application.yml` uses
   `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for other environments;
   provide those through the deployment environment and do not commit production credentials.

3. Stop PostgreSQL when finished:

   ```powershell
   docker compose down
   ```

   To also remove the local database volume, run `docker compose down -v`.

Flyway is enabled and applies versioned migrations from `classpath:db/migration`. Hibernate
schema generation is set to `validate`, so it checks the schema without creating tables.

## Health endpoints

`GET /health/live` reports whether the application process is running and does not query
PostgreSQL. `GET /health/ready` runs a lightweight database query and returns `200` only when
PostgreSQL is reachable; otherwise it returns `503`. Both endpoints are public.

Spring Boot Actuator health checks are also enabled at `/actuator/health`, including the
`/actuator/health/liveness` and `/actuator/health/readiness` probe groups. Readiness includes
the PostgreSQL health indicator.

## Structured request logging

Application logs are emitted as JSON to standard output. Each HTTP request accepts a
safe `X-Request-ID` value or receives a generated UUID; the selected ID is returned in the
response header and included in the JSON request-completion log. Request logs include the
HTTP method, path (without query parameters), response status, and duration in milliseconds.
Reservation confirmation, cancellation, replay, and decline events add the show and reservation
IDs when available. Authentication headers, JWTs, request bodies, and user identifiers are
never logged.

## Prometheus metrics

Micrometer metrics are exposed at `GET /actuator/prometheus`. The endpoint includes
`reservation_confirmed_total` for reservations committed to PostgreSQL and
`reservation_declined_total` tagged by the bounded `reason` values `seat_taken`,
`per_user_limit`, `idempotent_replay`, and `idempotency_conflict`. Confirmed reservations and
idempotent replays are counted only after their transaction commits. The `seats_available`
gauge queries the current database count of available seats when metrics are scraped.
Metrics intentionally do not include user, reservation, or idempotency identifiers.

## Local JWT testing

The `local` profile exposes a development-only token endpoint. It is not registered outside
that profile. Request a test token with `POST http://localhost:8080/dev/tokens` and JSON
`{"user_id": 123, "role": "USER"}` (use `ADMIN` for creating shows). Send the returned
`access_token` on protected requests as `Authorization: Bearer <token>`. The local signing
secret is for development only; configure a strong `JWT_SECRET` outside local development.

## PostgreSQL idempotency integration tests

With the local PostgreSQL service running, execute the integration cases for repeated requests,
different request bodies, concurrent same-key requests, and keys reused by different users:

```powershell
mvn "-DrunPostgresIntegrationTests=true" `
  "-Dintegration.jdbc-url=jdbc:postgresql://localhost:5433/seat_reservation" `
  "-Dintegration.jdbc-username=seat_reservation" `
  "-Dintegration.jdbc-password=seat_reservation_dev" test
```

Reservations lock the show row to serialize idempotency and per-user-limit checks, then lock
requested seat rows in sorted seat-number order. These locks and the database unique constraints
enforce all-or-nothing reservations and return conflicts for competing requests; no external
payment call is made inside the transaction.

## Reservation cancellation

`POST /reservations/{reservationId}/cancel` requires a Bearer token and is restricted to the
reservation owner. Confirmed reservations can be cancelled; repeating cancellation returns the
same `CANCELLED` response. Cancellation locks the show's row, the reservation, and its assigned
seats in one transaction, then marks the reservation cancelled and releases only seats still
assigned to that reservation. Reservation-seat links are retained as history independently of
the seat's current owner, so a released seat is immediately available to another user without
changing the prior reservation response. Pending or expired reservations are not cancellable in
this assignment model.
