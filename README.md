# Seat Reservation

## Local PostgreSQL development

Prerequisites: Docker Compose, Java 21 or later, and Maven.

1. Create your local Compose environment file:

   ```powershell
   Copy-Item .env.example .env
   ```

   `.env.example` contains local-only development values. Change them if needed; never use
   those values in production. Compose persists database files in the `postgres_data` volume.

2. Build and start PostgreSQL and the application together:

   ```powershell
   docker compose up --build
   ```

   Compose waits for PostgreSQL's `pg_isready` healthcheck before starting the app. The app
   connects to the `postgres` service on the Compose network, retries its container start if
   necessary, and reports healthy only after its PostgreSQL-backed readiness check succeeds.
   PostgreSQL is published on `localhost:5433` by default and the application on
   `http://localhost:8080` (`APP_PORT` changes the host port).

3. Stop the application and PostgreSQL:

   ```powershell
   docker compose down
   ```

   Database files remain in the persistent `postgres_data` volume. Remove the volume as well
   only when you want to delete local database data:

   ```powershell
   docker compose down -v
   ```

   Keep `.env` local and untracked. It contains development-only values; provide production
   credentials and JWT secrets through your deployment's secret manager, never in the image or
   committed Compose files. Other application deployments use `DATABASE_URL`,
   `DATABASE_USERNAME`, and `DATABASE_PASSWORD`.

Flyway is enabled and applies versioned migrations from `classpath:db/migration`. Hibernate
schema generation is set to `validate`, so it checks the schema without creating tables.

## Building and running the application container

Build the multi-stage image from the repository root:

```powershell
docker build -t seat-reservation:local .
```

Run it with deployment-provided environment variables (do not bake credentials into the image):

```powershell
docker run --rm -p 8080:8080 `
  -e DATABASE_URL="jdbc:postgresql://<database-host>:5432/<database-name>" `
  -e DATABASE_USERNAME="<database-user>" `
  -e DATABASE_PASSWORD="<database-password>" `
  -e JWT_SECRET="<strong-secret-of-at-least-32-bytes>" `
  seat-reservation:local
```

The database host must be reachable from inside the container. The image uses a small Java 21
Alpine runtime as a non-root user, exposes port 8080, and sets container-aware JVM heap limits.
Configure orchestrator liveness and readiness probes with `/health/live` and `/health/ready`;
PostgreSQL credentials and the JWT signing secret are supplied only at runtime.

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

## PostgreSQL concurrency integration tests

The integration suite uses Testcontainers to start an isolated PostgreSQL 16 instance, applies
the Flyway migrations, and exercises concurrent reservations, idempotency, per-user limits, lock
ordering, cancellation races, and show reconciliation. Docker must be available to run these
tests. Testcontainers disables this integration test class automatically when Docker is
unavailable:

```powershell
mvn "-Dtest=ReservationIdempotencyIntegrationTest" test
```

Reservations lock the show row to serialize idempotency and per-user-limit checks, then lock
requested seat rows in sorted seat-number order. These locks and the database unique constraints
enforce all-or-nothing reservations and return conflicts for competing requests; no external
payment call is made inside the transaction.

## Reservation concurrency burst script

Use a local or isolated test deployment with the `local` profile enabled; the script creates a
new show and requires the development-only `POST /dev/tokens` endpoint. It creates unique test
users and access tokens, then runs concurrent hot-seat, same-key idempotency, single-user limit,
overlapping multi-seat, opposite seat-order, and cancellation-race scenarios. It checks
reservation responses against final show state and exits non-zero if an invariant fails. The
generated show remains in the database.

```powershell
python scripts/burst.py http://localhost:8080 --requests 500 --workers 100
```

`--requests` is the request count in each of the burst scenarios, and must exceed the configured
per-user limit. `--workers` controls simultaneous requests. They can also be configured through
`BURST_REQUESTS`, `BURST_WORKERS`, `BURST_PER_USER_LIMIT`, and `BURST_TIMEOUT_SECONDS`.
Avoid running this load generator against production.

## Reservation cancellation

`POST /reservations/{reservationId}/cancel` requires a Bearer token and is restricted to the
reservation owner. Confirmed reservations can be cancelled; repeating cancellation returns the
same `CANCELLED` response. Cancellation locks the show's row, the reservation, and its assigned
seats in one transaction, then marks the reservation cancelled and releases only seats still
assigned to that reservation. Reservation-seat links are retained as history independently of
the seat's current owner, so a released seat is immediately available to another user without
changing the prior reservation response. Pending or expired reservations are not cancellable in
this assignment model.
