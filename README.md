# Seat Reservation API

A Spring Boot REST API for creating shows and reserving their seats. PostgreSQL is the source of
truth for seat availability, idempotency, reservation ownership, and reservation limits.
Reservations and cancellations use database transactions and row-level locks so concurrent
requests cannot create conflicting seat assignments.

## Contents

- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [Local setup](#local-setup)
- [Environment variables](#environment-variables)
- [Database and migrations](#database-and-migrations)
- [API reference](#api-reference)
- [Authentication](#authentication)
- [Reservation semantics](#reservation-semantics)
- [Idempotency](#idempotency)
- [Concurrency and PostgreSQL locking](#concurrency-and-postgresql-locking)
- [Multi-seat reservations](#multi-seat-reservations)
- [Cancellation](#cancellation)
- [Health checks](#health-checks)
- [Prometheus metrics](#prometheus-metrics)
- [Structured logging](#structured-logging)
- [Docker](#docker)
- [Concurrency burst script](#concurrency-burst-script)
- [PostgreSQL integration tests](#postgresql-integration-tests)
- [Deployment](#deployment)

## Architecture

```text
HTTP client
    |
    v
Spring MVC controllers --> request validation / DTOs
    |
    v
Spring Security JWT filter --> authenticated user and role
    |
    v
Transactional services --> Spring Data JPA repositories
    |                              |
    +---- row locks / constraints -+
                                   v
                              PostgreSQL
                         Flyway-managed schema
```

Controllers expose DTOs rather than persistence entities. The reservation service derives the
user identity from the authenticated security context. PostgreSQL holds the authoritative show,
seat, reservation, reservation-seat-history, and idempotency records. There is no payment
provider integration in this project.

## Technology stack

- Java 21 and Spring Boot 3.3
- Spring MVC, Bean Validation, and Spring Security
- Signed HS256 JWTs
- Spring Data JPA / Hibernate
- PostgreSQL 16 for local Compose and concurrency-test examples
- Flyway versioned SQL migrations
- Spring Boot Actuator and Micrometer Prometheus registry
- Docker multi-stage build and Docker Compose
- JUnit 5, Spring Boot Test, and Testcontainers for PostgreSQL integration tests

## Local setup

Prerequisites: Docker Compose, Java 21, and Maven. Python 3 is only needed to run the optional
HTTP burst script directly.

1. Create a local environment file:

   ```powershell
   Copy-Item .env.example .env
   ```

   The example values are for local development only. `.env` is ignored by Git.

2. Build and start PostgreSQL and the application:

   ```powershell
   docker compose up --build
   ```

   PostgreSQL is published at `localhost:5433` by default and the application at
   `http://localhost:8080`. The application starts after PostgreSQL passes its healthcheck.
   Its own healthcheck uses the database-backed readiness endpoint.

   If port 8080 is already in use, set a different host port before starting:

   ```powershell
   $env:APP_PORT = "18080"
   docker compose up --build
   ```

3. Stop the containers while retaining database files:

   ```powershell
   docker compose down
   ```

   To also delete the local PostgreSQL volume and all data in it:

   ```powershell
   docker compose down -v
   ```

For running the application from an IDE or Maven instead of its container, start only
PostgreSQL with `docker compose up -d postgres`, then launch Spring Boot with the `local`
profile. Set `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT` in the
application process environment to match `.env`. The local profile connects to `localhost` on
the configured port (default `5433`).

## Environment variables

| Variable | Used by | Description |
|---|---|---|
| `POSTGRES_DB` | Compose / `local` profile | Local database name |
| `POSTGRES_USER` | Compose / `local` profile | Local database user |
| `POSTGRES_PASSWORD` | Compose / `local` profile | Local database password; set in ignored `.env` |
| `POSTGRES_PORT` | Compose / `local` profile | Host-side PostgreSQL port; defaults to `5433` |
| `POSTGRES_HOST` | `local` profile | Database host; Compose sets it to `postgres`, local default is `localhost` |
| `APP_PORT` | Compose | Host-side application port; defaults to `8080` |
| `DATABASE_URL` | Default Spring profile / deployment | Full JDBC URL, for example `jdbc:postgresql://db-host:5432/seat_reservation` |
| `DATABASE_USERNAME` | Default Spring profile / deployment | Database username |
| `DATABASE_PASSWORD` | Default Spring profile / deployment | Database password |
| `JWT_SECRET` | Default Spring profile / optional local override | HMAC secret; at least 32 UTF-8 bytes |
| `JAVA_TOOL_OPTIONS` | JVM / container | Optional JVM options; the Docker image sets container-aware heap options |

The default Spring profile requires `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`,
and `JWT_SECRET`. The `local` profile uses the `POSTGRES_*` variables and has a development-only
fallback JWT secret so the supplied Compose setup works with `.env.example`. Do not reuse local
database values or the local JWT secret outside development. Do not put production credentials
in the image, Compose file, or repository.

## Database and migrations

Flyway owns the schema in `src/main/resources/db/migration`:

- `V1__initial_schema.sql` creates shows, seats, reservations, reservation-seat links, and
  idempotency keys, with their checks, foreign keys, unique constraints, and lookup indexes.
- `V2__add_public_uuids.sql` adds unique UUID public identifiers. Database relationships use
  internal `BIGINT` IDs; API resource IDs are UUIDs.
- `V3__preserve_reservation_seat_history.sql` allows reservation-seat links to remain as history
  after a cancellation releases a seat for a later reservation.

Hibernate is configured with `ddl-auto: validate`; it checks mappings and does not create or
update the schema. Monetary values are integer paise stored as PostgreSQL `BIGINT` and represented
as Java `Long`; floating-point money is not used.

## API reference

All application endpoints use the same base URL, such as `http://localhost:8080`.

| Method and path | Access | Success |
|---|---|---|
| `POST /shows` | `ADMIN` JWT | `201 Created`; creates show and all seats transactionally |
| `GET /shows/{showId}` | Public | `200 OK`; current show, seat counts, and per-seat state; `404` if show is unknown |
| `POST /shows/{showId}/reserve` | Authenticated JWT | `201 Created`; confirms a reservation or replays its idempotent result; conflicts return `409` |
| `POST /reservations/{reservationId}/cancel` | Authenticated reservation owner | `200 OK`; returns `CANCELLED`; non-owner is forbidden |
| `POST /dev/tokens` | Public, `local` profile only | `200 OK`; creates a development JWT |
| `GET /health/live` | Public | `200 OK` while the application process responds |
| `GET /health/ready` | Public | `200 OK` if PostgreSQL responds to a query; otherwise `503` |
| `GET /actuator/health` | Public | Actuator health document |
| `GET /actuator/health/liveness` | Public | Actuator liveness probe |
| `GET /actuator/health/readiness` | Public | Actuator readiness probe, including the database indicator |
| `GET /actuator/prometheus` | Public | Prometheus text exposition |

### Create a show

Request fields:

| Field | Type | Validation / behavior |
|---|---|---|
| `name` | string | Required and non-blank |
| `seats` | array of strings | Required, non-empty, non-blank entries, no duplicates |
| `price_paise` | integer | Required and non-negative |
| `per_user_limit` | integer | Optional; defaults to `4`, if provided must be positive |

Example response:

```json
{
  "show_id": "e18ec2e8-8fb8-43bf-ba4b-770120d68e5d",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "seats": ["A1", "A2", "A3"]
}
```

New seats start `AVAILABLE`. A show and its seats are created in one transaction.

### Get show state

The response includes `show_id`, `name`, `price_paise`, `total_seats`, `available`, `held`,
`confirmed`, and a `seats` array containing each seat number and status. Counts are calculated
from the current database rows. The endpoint sends `Cache-Control: no-store`.

Example:

```json
{
  "show_id": "e18ec2e8-8fb8-43bf-ba4b-770120d68e5d",
  "name": "friday-night",
  "price_paise": 25000,
  "total_seats": 3,
  "available": 2,
  "held": 0,
  "confirmed": 1,
  "seats": [
    {"seat": "A1", "status": "CONFIRMED"},
    {"seat": "A2", "status": "AVAILABLE"},
    {"seat": "A3", "status": "AVAILABLE"}
  ]
}
```

### Reserve seats

`POST /shows/{showId}/reserve` accepts:

```json
{
  "seats": ["A12", "A13"],
  "idempotency_key": "a-unique-client-generated-key"
}
```

The seat list must be non-empty, contain non-blank unique values, and the key must be non-blank.
The user ID is taken from the verified JWT, not the request body. Success is `201 Created` with
`reservation_id`, `show_id`, `user_id`, `seats`, `amount_paise`, and `status`.

### Error responses

Controller-handled validation and domain errors currently use `{ "error": "...", "message": "..." }`.
The status/code mappings include:

| HTTP | Cases |
|---|---|
| `400` | Malformed JSON or bean validation failure |
| `401` | Missing or invalid bearer authentication on a protected endpoint |
| `403` | A non-admin attempts `POST /shows`, or a user attempts to cancel another user's reservation |
| `404` | Unknown show, reservation, or requested seat |
| `409` | Seat unavailable, per-user limit exceeded, idempotency conflict, or non-cancellable reservation |
| `503` | PostgreSQL unavailable at `/health/ready` |

Authentication failures are handled by Spring Security rather than the MVC exception advice, so
their response body is not guaranteed to use the controller error shape. Unexpected application
errors are not described as domain conflicts.

## Authentication

Protected requests use `Authorization: Bearer <JWT>`. JWTs are signed with HS256 and must include
a positive numeric `user_id` claim. The filter validates the signature and claims, then places
the authenticated principal and role in Spring Security's context. Reservation ownership and
per-user counting use that authenticated `user_id`; a client-supplied request-body `user_id`
does not establish identity.

`POST /shows` requires the `ADMIN` role. Reservation and cancellation require an authenticated
user. Show reads, health checks, metrics, and the local token utility are public under the
current security configuration.

`POST /dev/tokens` exists only with the `local` profile active. It accepts
`{"user_id":123,"role":"USER"}` or role `ADMIN`; the role defaults to `USER`. The token lifetime
is one hour. This endpoint and local signing secret are for development/testing, not production
identity issuance.

## Reservation semantics

- A reservation confirms all requested seats or none.
- Amount is `show.price_paise * number of seats`, calculated using integer arithmetic.
- Newly confirmed reservations have status `CONFIRMED`.
- A seat already held or confirmed cannot be reserved.
- The configured per-user limit is a count of currently assigned seats for that show. The default
  limit for a show is four seats per user.
- No payment or other external network call is made in the database transaction.

### Idempotency

An idempotency key is scoped to `(user_id, show_id)`. The database enforces a unique constraint on
`(user_id, show_id, idempotency_key)`; application-side lookup is not the only protection against
duplicate creation.

The service hashes the requested seat list deterministically with SHA-256 after sorting the seat
numbers. Thus reordering the same requested seat set is treated as the same payload. A matching
key and hash returns the existing reservation rather than inserting a second one. Reusing the
same key for a different seat set returns `409 Conflict`. The same key can be used independently
by another user or for another show.

### Concurrency and PostgreSQL locking

The reservation transaction first obtains a pessimistic write lock on the show row through a
Spring Data JPA query annotated with `@Lock(PESSIMISTIC_WRITE)`. On PostgreSQL this is a row-level
write lock (`SELECT ... FOR UPDATE`). This is deliberately a per-show serialization point: a
reservation for the same show cannot pass the validation/count phase concurrently with another
reservation for that show.

After acquiring the show lock, a request checks the idempotency record, locks its requested seat
rows, verifies that they are available, checks the user's existing seat count, and writes the
reservation, seat assignments, reservation-seat records, and idempotency record in the same
transaction. Locks remain held until commit or rollback.

**Why 500 API requests cannot reserve the same seat:** every reservation transaction for that
show first acquires the same PostgreSQL show-row write lock. One transaction proceeds, confirms
the seat, and commits. Waiting transactions then acquire the lock and read the committed seat
state as unavailable, so they return the seat-conflict response (`409`) instead of creating
another reservation. The seat-row `PESSIMISTIC_WRITE` lock is also acquired before the status
check and protects the selected seat rows directly. These locks are database row locks requested
through JPA (`FOR UPDATE`-class locking), held until commit or rollback; the guarantee comes
from database transactions, not Java `synchronized` blocks. It applies to writes performed
through this reservation API.

For a request containing several seats, the repository query orders seat rows by `seat_number`
ascending before taking their pessimistic locks. Every request therefore acquires overlapping
seat locks in the same order. Requests for `[A1, A2]` and `[A2, A1]` both lock `A1` before `A2`,
avoiding the circular wait that can cause a deadlock when transactions lock those rows in
opposite orders.

### Per-user limits

The same show-row lock is held while the service counts the authenticated user's currently
assigned seats and compares that count plus the requested seat count with the show's limit.
Another reservation for that show cannot check against a stale pre-commit count concurrently.
Cancellation takes the same show lock before releasing seats, so reservation and cancellation
updates for a show share the lock order. This is why multiple concurrent requests by one user
cannot all pass a limit check based on the same old count.

### Multi-seat all-or-nothing behavior

Seat existence and availability are checked for the full requested set while the transaction
holds the show and seat locks. Only after all seats and the per-user limit pass does it insert the
reservation and assign the seats. A missing, taken, or invalid seat, limit violation, or database
failure rolls back the transaction; the request does not keep a subset of its seats.

## Cancellation

`POST /reservations/{reservationId}/cancel` is limited to the authenticated reservation owner.
Confirmed reservations may be cancelled. Repeating cancellation for an already cancelled
reservation returns a successful `CANCELLED` response. A non-owner receives `403`; unknown
reservations receive `404`; statuses other than confirmed or already cancelled are rejected with
`409`.

Cancellation locks the show's row, the reservation row, and the reservation's currently assigned
seat rows. It releases only seats that still reference that reservation and updates the
reservation status in the same transaction. Reservation-seat links are retained as history,
while a released seat becomes available for a later reservation. It does not delete the original
reservation history.

## Health checks

- `GET /health/live` checks that the application can serve a request; it does not query
  PostgreSQL.
- `GET /health/ready` runs `SELECT 1` through the configured datasource. It returns `200` only
  when that query succeeds and returns `503` when the database call fails.
- Actuator also exposes `/actuator/health`, `/actuator/health/liveness`, and
  `/actuator/health/readiness`. The readiness group includes Spring Boot's PostgreSQL
  (`db`) health indicator.

## Prometheus metrics

Prometheus exposition is available at `GET /actuator/prometheus`. Application metrics include:

| Prometheus metric | Meaning |
|---|---|
| `reservation_confirmed_total` | Reservations whose transaction committed |
| `reservation_declined_total{reason="seat_taken"}` | Requests declined because a requested seat was unavailable |
| `reservation_declined_total{reason="per_user_limit"}` | Requests declined by the per-user seat limit |
| `reservation_declined_total{reason="idempotent_replay"}` | Matching idempotent retries |
| `reservation_declined_total{reason="idempotency_conflict"}` | Key reused with a different payload |
| `seats_available` | Current available-seat count read from PostgreSQL when scraped |

Confirmed and replay counters are incremented after the relevant reservation transaction commits.
The `reason` label has a fixed set of values; user IDs, reservation IDs, and keys are not used as
metric labels.

## Structured logging

The application writes JSON logs to standard output. Each HTTP request accepts a safe
`X-Request-ID` value or generates a UUID, returns the selected ID in the response header, and
includes it in the request completion log. Request logs include method, path (without query
string), response status, and duration in milliseconds.

Reservation lifecycle events include show and reservation UUIDs where available. The request
logging filter does not log headers or bodies; JWTs, authorization values, request bodies, and
user IDs are not included in these request or reservation event logs.

## Docker

The multi-stage Dockerfile builds with Maven and Java 21, then runs the Spring Boot application
on a Java 21 Alpine JRE image as a non-root user. It exposes port `8080` and sets JVM heap sizing
based on container memory. `.dockerignore` excludes local environment files and build/development
artifacts.

For a standalone application container, provide deployment environment values at runtime:

```powershell
docker build -t seat-reservation:local .
docker run --rm -p 8080:8080 `
  -e DATABASE_URL="jdbc:postgresql://<database-host>:5432/<database-name>" `
  -e DATABASE_USERNAME="<database-user>" `
  -e DATABASE_PASSWORD="<database-password>" `
  -e JWT_SECRET="<secret-with-at-least-32-utf-8-bytes>" `
  seat-reservation:local
```

The database host must be routable from inside the container. No production database or JWT
credentials are copied into the image.

## Concurrency burst script

The optional `scripts/burst.py` HTTP load script creates a test show and development JWT users,
then exercises hot-seat contention, concurrent same-key retries, same-key/different-body
conflicts, per-user limits, overlapping and opposite-order multi-seat requests, and a cancellation
race. It checks responses against final show state and exits non-zero on a failed invariant. The
test show remains in the database. Run only against a local or isolated test deployment with the
`local` profile enabled; it uses the development-only `/dev/tokens` endpoint.

```powershell
python scripts/burst.py http://localhost:8080 --requests 100 --workers 64
```

`--requests` is the request count for each burst and must be greater than the per-user limit.
`--workers` caps simultaneous HTTP requests. Environment alternatives are `BURST_REQUESTS`,
`BURST_WORKERS`, `BURST_PER_USER_LIMIT`, and `BURST_TIMEOUT_SECONDS`. When the application is
reachable only inside Compose, the script can be run from a Python container attached to the
Compose network; use the Compose service name `app` as its base URL.

## PostgreSQL integration tests

`ReservationIdempotencyIntegrationTest` uses Testcontainers to start PostgreSQL 16, applies the
real Flyway migrations, and exercises the database-backed reservation behavior. It includes
sequential and concurrent idempotency, 100 concurrent authenticated HTTP requests for one seat,
same-user limit enforcement, competing and overlapping seats, reverse seat order, cancellation
and reservation races, and show reconciliation. Latches synchronize concurrent workers; test
timeouts are bounds on completion, not sleeps used to coordinate correctness.

Docker must be available to run the integration class. Testcontainers is configured to disable
that class when Docker is unavailable. Run it with:

```powershell
mvn "-Dtest=ReservationIdempotencyIntegrationTest" test
```

Run the regular test suite with:

```powershell
mvn test
```

## Example curl commands

The commands below use `curl.exe` and PowerShell variables. With the default Compose port, use
`http://localhost:8080`. If `APP_PORT` was changed, use that host port instead. Token requests
work only when the application runs with the `local` profile.

Get a local admin token and create a show:

```powershell
$baseUrl = "http://localhost:8080"
$adminToken = (curl.exe -sS -X POST "$baseUrl/dev/tokens" `
  -H "Content-Type: application/json" `
  -d '{"user_id":1001,"role":"ADMIN"}' | ConvertFrom-Json).access_token

$show = curl.exe -sS -X POST "$baseUrl/shows" `
  -H "Authorization: Bearer $adminToken" `
  -H "Content-Type: application/json" `
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}' |
  ConvertFrom-Json
$showId = $show.show_id
$showId
```

Read the current show state:

```powershell
curl.exe -sS "$baseUrl/shows/$showId"
```

Get a user token and reserve a seat. Keep the idempotency key for retries of this logical
request; use a new key for a distinct reservation request:

```powershell
$userToken = (curl.exe -sS -X POST "$baseUrl/dev/tokens" `
  -H "Content-Type: application/json" `
  -d '{"user_id":2001,"role":"USER"}' | ConvertFrom-Json).access_token
$idempotencyKey = [guid]::NewGuid().ToString()
$reservation = curl.exe -sS -X POST "$baseUrl/shows/$showId/reserve" `
  -H "Authorization: Bearer $userToken" `
  -H "Content-Type: application/json" `
  -d "{`"seats`":[`"A1`"],`"idempotency_key`":`"$idempotencyKey`"}" |
  ConvertFrom-Json
$reservation
```

Cancel that user's reservation:

```powershell
curl.exe -sS -X POST "$baseUrl/reservations/$($reservation.reservation_id)/cancel" `
  -H "Authorization: Bearer $userToken"
```

Check liveness, readiness, and Prometheus output:

```powershell
curl.exe -i "$baseUrl/health/live"
curl.exe -i "$baseUrl/health/ready"
curl.exe "$baseUrl/actuator/prometheus"
```

## Deployment

The repository provides an application image and local Compose setup; it does not include a
cloud-specific deployment manifest. For a deployment:

1. Build and publish the application image to the chosen container registry.
2. Provision a PostgreSQL database reachable from the application network.
3. Inject `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and a strong
   `JWT_SECRET` (at least 32 UTF-8 bytes) from the deployment's secret manager.
4. Start the container on port `8080`. Flyway runs the migrations during application startup;
   Hibernate validates the migrated schema.
5. Configure the platform's liveness probe to use `/health/live` and readiness probe to use
   `/health/ready` or `/actuator/health/readiness`. Use readiness to keep instances out of
   service while PostgreSQL is unavailable.
6. Configure Prometheus to scrape `/actuator/prometheus` if metrics collection is needed.

The local `local` Spring profile and `/dev/tokens` utility are for development/testing and should
not be enabled or exposed as production identity mechanisms. Production JWT issuance and secret
rotation must be provided by the deployment's identity/security design; this repository does not
provide a production user store or password authentication.
