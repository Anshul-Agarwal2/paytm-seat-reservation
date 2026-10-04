# Paytm Backend Take-Home Assignment

## 1. Atomic Decision

A reservation is decided inside one Spring-managed PostgreSQL transaction in
`ReservationTransaction.create`. The transaction first acquires a pessimistic write lock on
the show row. JPA's `PESSIMISTIC_WRITE` lock is translated to PostgreSQL row-level locking
(the `SELECT ... FOR UPDATE` class of lock). It then locks the requested seat rows, checks
their states and the user's current seat count, and writes the reservation, seat assignments,
reservation-seat history, and idempotency record before commit.

A read-then-write flow without a lock is unsafe: two transactions could both read `AVAILABLE`
and both make a decision based on that stale observation. Here, the first transaction holds
the relevant locks through commit or rollback. Contending transactions wait and then observe
the committed state; an already-assigned seat is rejected with a conflict. The show lock also
serializes all reservation decisions for that show, which protects the count-and-limit check.
This guarantee applies to writes that use this transaction path; direct database writes that
bypass the application are outside that protocol.

## 2. Multi-seat Reservations

Each requested seat is validated and assigned within the same transaction. If any seat is
missing, unavailable, or the request exceeds the user's limit, the transaction rolls back, so
no subset of the requested seats is committed.

Seat numbers are sorted before the lock query, whose ordering is also ascending. Every
multi-seat request therefore acquires overlapping seat locks in the same order, including
requests submitted as `[A1, A2]` and `[A2, A1]`. A consistent lock order prevents the
crossed-lock cycle that can deadlock when two transactions acquire the same rows in opposite
orders.

## 3. Idempotency

An idempotency row stores the authenticated `user_id`, internal `show_id`, supplied key,
SHA-256 request hash, and reservation ID. The database enforces uniqueness on
`(user_id, show_id, idempotency_key)`, so keys are isolated between users and shows.

The hash is computed from a canonical, sorted seat list with length-prefixed values, so
reordering the same seats does not change the request identity. Reusing a key with the same
hash returns the original reservation; reusing it with different seats returns HTTP 409.
Concurrent duplicates for a show are serialized by the show lock. The unique constraint is
still the final correctness guard: if competing inserts encounter it, the service recovers the
winning idempotency record and replays its reservation when the hash matches, or returns a
conflict when it does not.

## 4. Per-user Limit

The limit check is not an unprotected count. Every reservation for a show first locks the same
show row and holds that lock until transaction completion. Therefore, two transactions for
that show cannot simultaneously count the same old state and both pass: the second waits,
then runs its count after the first commits (or rolls back). The count uses currently assigned
seats belonging to the authenticated user; the new request's seats are added to that count
before the limit is checked. This protects same-user concurrent requests as well as requests
from different users competing for the same show.

## 5. Cancellation

Cancellation is transactional. It locks the show, then the reservation, verifies the
authenticated owner, and locks that reservation's assigned seats before changing state. It
marks those seats available and the reservation cancelled in the same commit. Repeated
cancellation returns the already-cancelled status. The seat is released only if it is still
confirmed and assigned to that reservation; the historical reservation-seat record is
retained. Reservation and cancellation both take the show lock first, so they cannot race
through conflicting state changes for the same show.

## 6. Consistency vs Availability

PostgreSQL is authoritative for seat availability, reservation ownership, and idempotency.
If it becomes unavailable, the application cannot safely confirm new reservations from a
cache or local process state; it must fail closed rather than risk double booking or losing a
committed result. `/health/ready` probes PostgreSQL and returns HTTP 503 when that probe
fails, while `/health/live` does not depend on the database. The application does not provide
an alternate reservation store or database failover configuration. The readiness endpoint
reports dependency health; it does not itself guarantee that every database failure on a
reservation request is translated to HTTP 503.

## 7. Observability

Micrometer exposes a committed-reservation counter, declined/replay counters tagged with
bounded reasons (`seat_taken`, `per_user_limit`, `idempotent_replay`, and
`idempotency_conflict`), and a database-backed `seats.available` gauge at
`/actuator/prometheus`. The confirmed counter is incremented after transaction commit, not
before it.

Structured JSON request logs include method, endpoint, response status, duration, and
`request_id`. The filter accepts a syntactically safe `X-Request-ID` or generates one, echoes
it in the response, and places it in the logging context. Reservation event logs include
show and reservation IDs where available; request bodies, JWTs, and authentication headers
are not logged.

No alert rules are included in this repository. In production, a 2am page should be driven
by symptoms such as sustained readiness failures, elevated 5xx rates, a sudden rise in
reservation transaction latency/timeouts, or database saturation/connection exhaustion.
Seat-taken and per-user-limit conflicts are expected contention outcomes and should be
interpreted as traffic signals, not automatically treated as service failures.

## 8. Testing

The PostgreSQL Testcontainers integration class includes synchronized concurrent HTTP and
service-level cases: 100 users competing for one seat, 100 concurrent multi-seat requests,
reversed seat ordering, overlapping requests, same-user limit stress, 50 same-key retries,
different-payload idempotency conflict, independent users using the same key, and a
cancellation/reservation race. Assertions cover one-winner contention, conflict/no-server-
error outcomes, same reservation on retries, and all-or-nothing assignment.

The burst script exercises a hot-seat storm, repeated idempotency keys, per-user concurrency,
and cancellation contention against a running service. Integration checks also reconcile
show totals so `available + held + confirmed == total`, and verify that a seat is not
confirmed for multiple users. The tests are configured to use PostgreSQL 16 through
Testcontainers; a Docker-capable environment is required to execute that class.

## 9. AI Usage

GitHub Copilot was used to assist with scaffolding, repetitive boilerplate, test-generation
work, and documentation. I reviewed the resulting code and made the final decisions about
the concurrency model, transaction boundaries, lock ordering, idempotency constraints, and
per-user limit protection manually. Those decisions were checked against the implementation
and represented in PostgreSQL-backed concurrency tests; the Testcontainers tests require a
working Docker connection to run.

## 10. What I Would Do Next

- Add payment as a separate workflow outside the database reservation transaction, with
  explicit pending/confirmed/expired states and idempotent payment callbacks.
- Deploy multiple stateless application instances with managed PostgreSQL, connection-pool
  limits, backups, failover, and tested recovery procedures.
- Scale reads carefully: add read replicas only for endpoints where bounded staleness is
  acceptable; keep reservation decisions on the primary.
- Measure database bottlenecks before adding partitioning or sharding. If required, partition
  or shard by show while preserving a single authoritative write path for each show.
- Add rate limiting and abuse controls at the gateway, plus distributed tracing across HTTP,
  PostgreSQL, and downstream services.
- Introduce Kafka/event-driven processing for post-commit workflows using a transactional
  outbox, avoiding publishing an event for a transaction that later rolls back.
- Replace development token issuance with a production identity provider, key rotation,
  issuer/audience validation, and appropriately scoped authorization.
- Automate sustained and burst load tests in CI or a performance environment, and track
  latency, lock waits, error rates, and database capacity over time.
