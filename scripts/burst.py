#!/usr/bin/env python3
"""Exercise reservation concurrency invariants against a local/test deployment."""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Outcome:
    status: int | None
    body: dict[str, Any] | None
    error: str | None = None


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run concurrent hot-seat, idempotency-retry, and per-user-limit reservation bursts. "
            "Each scenario submits --requests reservations."
        )
    )
    parser.add_argument("base_url", help="Application origin, for example http://localhost:8080")
    parser.add_argument(
        "--requests",
        type=positive_int,
        default=int(os.getenv("BURST_REQUESTS", "300")),
        help="Concurrent reservations per scenario (default: BURST_REQUESTS or 300)",
    )
    parser.add_argument(
        "--workers",
        type=positive_int,
        default=int(os.getenv("BURST_WORKERS", "64")),
        help="Maximum simultaneous HTTP workers (default: BURST_WORKERS or 64)",
    )
    parser.add_argument(
        "--per-user-limit",
        type=positive_int,
        default=int(os.getenv("BURST_PER_USER_LIMIT", "4")),
        help="Show's per-user seat limit (default: BURST_PER_USER_LIMIT or 4)",
    )
    parser.add_argument(
        "--timeout",
        type=positive_float,
        default=float(os.getenv("BURST_TIMEOUT_SECONDS", "30")),
        help="HTTP timeout in seconds (default: BURST_TIMEOUT_SECONDS or 30)",
    )
    args = parser.parse_args()
    parsed_url = urlsplit(args.base_url)
    if parsed_url.scheme not in ("http", "https") or not parsed_url.netloc:
        parser.error("BASE_URL must be an absolute http:// or https:// URL")
    if args.requests <= args.per_user_limit:
        parser.error("--requests must be greater than --per-user-limit to exercise the limit")
    args.base_url = urlunsplit(
        (parsed_url.scheme, parsed_url.netloc, parsed_url.path.rstrip("/"), "", "")
    )
    return args


def decode_json(raw: bytes) -> dict[str, Any] | None:
    try:
        parsed = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    return parsed if isinstance(parsed, dict) else None


def http_json(
    base_url: str,
    path: str,
    *,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    token: str | None = None,
    timeout: float,
) -> Outcome:
    headers = {
        "Accept": "application/json",
        "X-Request-ID": str(uuid.uuid4()),
    }
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(f"{base_url}{path}", data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return Outcome(response.status, decode_json(response.read()))
    except HTTPError as response:
        return Outcome(response.code, decode_json(response.read()))
    except (URLError, TimeoutError, OSError) as error:
        return Outcome(None, None, str(error))


def require_success(outcome: Outcome, operation: str, expected: int = 200) -> dict[str, Any]:
    if outcome.status != expected or outcome.body is None:
        detail = outcome.error or json.dumps(outcome.body, sort_keys=True)
        raise RuntimeError(f"{operation} failed (HTTP {outcome.status}): {detail}")
    return outcome.body


def generate_token(
    base_url: str,
    user_id: int,
    role: str,
    timeout: float,
) -> str:
    response = http_json(
        base_url,
        "/dev/tokens",
        method="POST",
        payload={"user_id": user_id, "role": role},
        timeout=timeout,
    )
    body = require_success(response, f"Generating {role} token")
    token = body.get("access_token")
    if not isinstance(token, str) or not token:
        raise RuntimeError("Token endpoint did not return an access_token")
    return token


def reserve(
    base_url: str,
    show_id: str,
    token: str,
    seats: str | list[str],
    idempotency_key: str,
    timeout: float,
    start: threading.Event,
) -> Outcome:
    start.wait()
    requested_seats = [seats] if isinstance(seats, str) else seats
    return http_json(
        base_url,
        f"/shows/{show_id}/reserve",
        method="POST",
        payload={"seats": requested_seats, "idempotency_key": idempotency_key},
        token=token,
        timeout=timeout,
    )


def run_burst(
    base_url: str,
    show_id: str,
    requests: list[tuple[str, str | list[str], str]],
    *,
    workers: int,
    timeout: float,
) -> list[Outcome]:
    start = threading.Event()
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [
            executor.submit(
                reserve,
                base_url,
                show_id,
                token,
                seat,
                key,
                timeout,
                start,
            )
            for token, seat, key in requests
        ]
        start.set()
        return [future.result() for future in as_completed(futures)]


def cancel_reservation(
    base_url: str,
    reservation_id: str,
    token: str,
    timeout: float,
    start: threading.Event,
) -> Outcome:
    start.wait()
    return http_json(
        base_url,
        f"/reservations/{reservation_id}/cancel",
        method="POST",
        token=token,
        timeout=timeout,
    )


def run_cancel_reservation_race(
    base_url: str,
    show_id: str,
    reservation_id: str,
    owner_token: str,
    competitor_token: str,
    seat: str,
    timeout: float,
) -> tuple[Outcome, Outcome]:
    start = threading.Event()
    with ThreadPoolExecutor(max_workers=2) as executor:
        cancellation = executor.submit(
            cancel_reservation,
            base_url,
            reservation_id,
            owner_token,
            timeout,
            start,
        )
        competing_reservation = executor.submit(
            reserve,
            base_url,
            show_id,
            competitor_token,
            seat,
            f"cancel-race-{uuid.uuid4()}",
            timeout,
            start,
        )
        start.set()
        return cancellation.result(), competing_reservation.result()


def outcome_counts(outcomes: list[Outcome]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for outcome in outcomes:
        if outcome.status is None:
            counts["transport_error"] += 1
        elif outcome.status == 200:
            counts["200"] += 1
        elif outcome.status == 201:
            counts["201"] += 1
        elif outcome.status == 409:
            counts["409"] += 1
        elif 400 <= outcome.status < 500:
            counts["other_4xx"] += 1
        elif 500 <= outcome.status < 600:
            counts["5xx"] += 1
        else:
            counts["other"] += 1
    return counts


def print_distribution(outcomes: list[Outcome]) -> None:
    counts = outcome_counts(outcomes)
    print(
        "  "
        + ", ".join(
            f"{label}={counts.get(label, 0)}"
            for label in ("200", "201", "409", "other_4xx", "5xx", "transport_error", "other")
        )
        + f" (4xx total={counts.get('409', 0) + counts.get('other_4xx', 0)})"
    )


def check_all_statuses_are_expected(
    outcomes: list[Outcome],
    expected_statuses: set[int],
    scenario: str,
    failures: list[str],
) -> None:
    unexpected = [
        outcome
        for outcome in outcomes
        if outcome.status not in expected_statuses
    ]
    if unexpected:
        counts = outcome_counts(unexpected)
        failures.append(f"{scenario}: unexpected outcomes {dict(counts)}")
        for outcome in unexpected[:3]:
            if outcome.error:
                print(f"  {scenario} transport error: {outcome.error}", file=sys.stderr)


def unique_user_id(used: set[int]) -> int:
    while True:
        user_id = uuid.uuid4().int & ((1 << 63) - 1)
        if user_id > 0 and user_id not in used:
            used.add(user_id)
            return user_id


def main() -> int:
    args = parse_args()
    failures: list[str] = []
    used_user_ids: set[int] = set()
    admin_id = unique_user_id(used_user_ids)
    print(f"Target: {args.base_url}")
    print(
        f"Requests per scenario: {args.requests}; workers: {args.workers}; "
        f"per-user limit: {args.per_user_limit}"
    )

    admin_token = generate_token(args.base_url, admin_id, "ADMIN", args.timeout)
    hot_users = [unique_user_id(used_user_ids) for _ in range(args.requests)]
    retry_user = unique_user_id(used_user_ids)
    limited_user = unique_user_id(used_user_ids)
    overlap_users = [unique_user_id(used_user_ids) for _ in range(2)]
    order_users = [unique_user_id(used_user_ids) for _ in range(2)]
    cancellation_owner = unique_user_id(used_user_ids)
    cancellation_competitor = unique_user_id(used_user_ids)
    all_user_ids = (
        hot_users
        + [retry_user, limited_user, cancellation_owner, cancellation_competitor]
        + overlap_users
        + order_users
    )

    print(f"Generating {len(all_user_ids)} unique USER tokens...")
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        token_futures = {
            executor.submit(
                generate_token,
                args.base_url,
                user_id,
                "USER",
                args.timeout,
            ): user_id
            for user_id in all_user_ids
        }
        tokens = {
            token_futures[future]: future.result()
            for future in as_completed(token_futures)
        }

    seats = [
        "HOT-SEAT",
        "RETRY-SEAT",
        "RETRY-ALTERNATE",
        "OVERLAP-A",
        "OVERLAP-B",
        "OVERLAP-C",
        "ORDER-A",
        "ORDER-B",
        "CANCEL-SEAT",
        *[f"LIMIT-{index:06d}" for index in range(args.requests)],
    ]
    show_payload = {
        "name": f"burst-{uuid.uuid4()}",
        "seats": seats,
        "price_paise": 100,
        "per_user_limit": args.per_user_limit,
    }
    show_response = http_json(
        args.base_url,
        "/shows",
        method="POST",
        payload=show_payload,
        token=admin_token,
        timeout=args.timeout,
    )
    show_body = require_success(show_response, "Creating test show", expected=201)
    show_id = show_body.get("show_id")
    if not isinstance(show_id, str):
        raise RuntimeError("Create-show response did not contain show_id")
    print(f"Created isolated test show {show_id} with {len(seats)} seats.")

    hot_requests = [
        (tokens[user_id], "HOT-SEAT", f"hot-{uuid.uuid4()}")
        for user_id in hot_users
    ]
    retry_key = f"retry-{uuid.uuid4()}"
    retry_requests = [
        (tokens[retry_user], "RETRY-SEAT", retry_key)
        for _ in range(args.requests)
    ]
    limit_requests = [
        (tokens[limited_user], f"LIMIT-{index:06d}", f"limit-{uuid.uuid4()}")
        for index in range(args.requests)
    ]

    all_outcomes: list[Outcome] = []
    print(f"Hot-seat storm ({len(hot_requests)} requests to one seat):")
    hot_outcomes = run_burst(
        args.base_url, show_id, hot_requests, workers=args.workers, timeout=args.timeout
    )
    all_outcomes.extend(hot_outcomes)
    print_distribution(hot_outcomes)
    check_all_statuses_are_expected(hot_outcomes, {201, 409}, "Hot-seat storm", failures)
    hot_successes = [outcome for outcome in hot_outcomes if outcome.status == 201]
    if len(hot_successes) != 1:
        failures.append(f"Hot-seat storm: expected exactly one 201, got {len(hot_successes)}")
    if sum(outcome.status == 409 for outcome in hot_outcomes) != args.requests - 1:
        failures.append("Hot-seat storm: all losing requests must receive HTTP 409")

    print(f"Concurrent idempotent retry ({len(retry_requests)} same-key requests):")
    retry_outcomes = run_burst(
        args.base_url, show_id, retry_requests, workers=args.workers, timeout=args.timeout
    )
    all_outcomes.extend(retry_outcomes)
    print_distribution(retry_outcomes)
    check_all_statuses_are_expected(retry_outcomes, {201}, "Idempotent retry", failures)
    retry_ids = {
        outcome.body.get("reservation_id")
        for outcome in retry_outcomes
        if outcome.status == 201 and outcome.body is not None
    }
    if len(retry_ids) != 1 or None in retry_ids:
        failures.append("Idempotent retry: requests did not return the same reservation ID")
    retry_responses = [
        (
            outcome.body.get("reservation_id"),
            outcome.body.get("show_id"),
            outcome.body.get("user_id"),
            outcome.body.get("seats"),
            outcome.body.get("amount_paise"),
            outcome.body.get("status"),
        )
        for outcome in retry_outcomes
        if outcome.status == 201 and outcome.body is not None
    ]
    if len(set(map(repr, retry_responses))) != 1:
        failures.append("Idempotent retry: replay response fields differ")

    changed_body_outcome = http_json(
        args.base_url,
        f"/shows/{show_id}/reserve",
        method="POST",
        payload={
            "seats": ["RETRY-ALTERNATE"],
            "idempotency_key": retry_key,
        },
        token=tokens[retry_user],
        timeout=args.timeout,
    )
    all_outcomes.append(changed_body_outcome)
    print("Same idempotency key with a different seat payload:")
    print_distribution([changed_body_outcome])
    if changed_body_outcome.status != 409:
        failures.append(
            "Same idempotency key with a different body must return HTTP 409, "
            f"got {changed_body_outcome.status}"
        )

    print(f"Per-user limit burst ({len(limit_requests)} seats requested by one user):")
    limit_outcomes = run_burst(
        args.base_url, show_id, limit_requests, workers=args.workers, timeout=args.timeout
    )
    all_outcomes.extend(limit_outcomes)
    print_distribution(limit_outcomes)
    check_all_statuses_are_expected(limit_outcomes, {201, 409}, "Per-user limit burst", failures)
    limit_successes = [outcome for outcome in limit_outcomes if outcome.status == 201]
    if len(limit_successes) > args.per_user_limit:
        failures.append(
            f"Per-user limit burst: user received {len(limit_successes)} reservations, "
            f"limit is {args.per_user_limit}"
        )
    if len(limit_successes) != args.per_user_limit:
        failures.append(
            f"Per-user limit burst: expected {args.per_user_limit} successful seats, "
            f"got {len(limit_successes)}"
        )

    overlapping_requests = [
        (
            tokens[overlap_users[0]],
            ["OVERLAP-A", "OVERLAP-B"],
            f"overlap-{uuid.uuid4()}",
        ),
        (
            tokens[overlap_users[1]],
            ["OVERLAP-B", "OVERLAP-C"],
            f"overlap-{uuid.uuid4()}",
        ),
    ]
    print("Overlapping multi-seat requests:")
    overlapping_outcomes = run_burst(
        args.base_url,
        show_id,
        overlapping_requests,
        workers=args.workers,
        timeout=args.timeout,
    )
    all_outcomes.extend(overlapping_outcomes)
    print_distribution(overlapping_outcomes)
    check_all_statuses_are_expected(
        overlapping_outcomes, {201, 409}, "Overlapping multi-seat requests", failures
    )
    overlapping_successes = [
        outcome
        for outcome in overlapping_outcomes
        if outcome.status == 201 and outcome.body is not None
    ]
    if len(overlapping_successes) != 1:
        failures.append("Overlapping multi-seat requests must have exactly one winner")
    elif len(overlapping_successes[0].body.get("seats", [])) != 2:
        failures.append("Overlapping multi-seat conflict partially reserved its requested seats")

    opposite_order_requests = [
        (
            tokens[order_users[0]],
            ["ORDER-A", "ORDER-B"],
            f"order-{uuid.uuid4()}",
        ),
        (
            tokens[order_users[1]],
            ["ORDER-B", "ORDER-A"],
            f"order-{uuid.uuid4()}",
        ),
    ]
    print("Opposite-order multi-seat requests:")
    opposite_order_outcomes = run_burst(
        args.base_url,
        show_id,
        opposite_order_requests,
        workers=args.workers,
        timeout=args.timeout,
    )
    all_outcomes.extend(opposite_order_outcomes)
    print_distribution(opposite_order_outcomes)
    check_all_statuses_are_expected(
        opposite_order_outcomes, {201, 409}, "Opposite-order requests", failures
    )
    if sorted(outcome.status for outcome in opposite_order_outcomes) != [201, 409]:
        failures.append("Opposite-order requests must finish with one success and one conflict")
    opposite_order_successes = [
        outcome
        for outcome in opposite_order_outcomes
        if outcome.status == 201 and outcome.body is not None
    ]
    if (
        len(opposite_order_successes) == 1
        and len(opposite_order_successes[0].body.get("seats", [])) != 2
    ):
        failures.append("Opposite-order conflict partially reserved its requested seats")

    start_immediately = threading.Event()
    start_immediately.set()
    initial_cancel = reserve(
        args.base_url,
        show_id,
        tokens[cancellation_owner],
        "CANCEL-SEAT",
        f"cancel-initial-{uuid.uuid4()}",
        args.timeout,
        start_immediately,
    )
    cancelled_reservation_ids: set[str] = set()
    if initial_cancel.status == 201 and initial_cancel.body is not None:
        all_outcomes.append(initial_cancel)
        reservation_id = initial_cancel.body.get("reservation_id")
        if not isinstance(reservation_id, str):
            failures.append("Cancellation race setup omitted reservation_id")
        else:
            print("Cancellation racing with a competing reservation:")
            cancellation_outcome, competing_outcome = run_cancel_reservation_race(
                args.base_url,
                show_id,
                reservation_id,
                tokens[cancellation_owner],
                tokens[cancellation_competitor],
                "CANCEL-SEAT",
                args.timeout,
            )
            print("  cancel request:")
            print_distribution([cancellation_outcome])
            print("  competing reserve request:")
            print_distribution([competing_outcome])
            all_outcomes.extend([cancellation_outcome, competing_outcome])
            if cancellation_outcome.status != 200:
                failures.append(
                    "Cancellation race must return HTTP 200, "
                    f"got {cancellation_outcome.status}"
                )
            elif (
                cancellation_outcome.body is None
                or cancellation_outcome.body.get("status") != "CANCELLED"
            ):
                failures.append("Cancellation race did not report CANCELLED")
            else:
                cancelled_reservation_ids.add(reservation_id)
            if competing_outcome.status not in {201, 409}:
                failures.append(
                    "Competing reservation must return HTTP 201 or 409, "
                    f"got {competing_outcome.status}"
                )
    else:
        all_outcomes.append(initial_cancel)
        failures.append(
            "Cancellation race setup failed to reserve its seat: "
            f"HTTP {initial_cancel.status}"
        )

    print("Overall reservation outcomes:")
    print_distribution(all_outcomes)

    show_result = http_json(args.base_url, f"/shows/{show_id}", timeout=args.timeout)
    show_state = require_success(show_result, "Fetching test show")
    total = show_state.get("total_seats")
    available = show_state.get("available")
    held = show_state.get("held")
    confirmed = show_state.get("confirmed")
    if not all(isinstance(value, int) for value in (total, available, held, confirmed)):
        failures.append("Show state response is missing valid seat counts")
    elif available + held + confirmed != total:
        failures.append(
            f"Seat counts do not reconcile: {available}+{held}+{confirmed}!={total}"
        )

    seat_states = show_state.get("seats")
    if not isinstance(seat_states, list):
        failures.append("Show state response does not contain a seat list")
        seat_states = []
    state_by_seat: dict[str, str] = {}
    for seat_state in seat_states:
        if isinstance(seat_state, dict):
            name = seat_state.get("seat")
            status = seat_state.get("status")
            if isinstance(name, str) and isinstance(status, str):
                if name in state_by_seat:
                    failures.append(f"Show state lists seat {name} more than once")
                state_by_seat[name] = status

    seat_owners: dict[str, int] = {}
    reservation_owners: dict[str, int] = {}
    unique_confirmed_seats: set[str] = set()
    for outcome in all_outcomes:
        if outcome.status != 201 or outcome.body is None:
            continue
        response_user = outcome.body.get("user_id")
        reservation_id = outcome.body.get("reservation_id")
        if reservation_id in cancelled_reservation_ids:
            continue
        response_seats = outcome.body.get("seats")
        if not isinstance(response_user, int) or not isinstance(reservation_id, str):
            failures.append("A successful reservation response omitted user_id or reservation_id")
            continue
        if not isinstance(response_seats, list) or not all(
            isinstance(seat, str) for seat in response_seats
        ):
            failures.append(f"Reservation {reservation_id} returned an invalid seats list")
            continue
        previous_user = reservation_owners.setdefault(reservation_id, response_user)
        if previous_user != response_user:
            failures.append(f"Reservation {reservation_id} was returned for multiple users")
        for seat in response_seats:
            previous_owner = seat_owners.setdefault(seat, response_user)
            if previous_owner != response_user:
                failures.append(f"Seat {seat} was confirmed for multiple users")
            unique_confirmed_seats.add(seat)
            if state_by_seat.get(seat) != "CONFIRMED":
                failures.append(f"Successful reservation seat {seat} is not CONFIRMED in show state")

    confirmed_in_state = {
        name for name, status in state_by_seat.items() if status == "CONFIRMED"
    }
    if unique_confirmed_seats != confirmed_in_state:
        failures.append(
            "Confirmed seats in show state do not match unique successful reservation seats"
        )
    if state_by_seat.get("RETRY-ALTERNATE") != "AVAILABLE":
        failures.append("Different-body idempotency conflict unexpectedly reserved its seat")
    if "CANCEL-SEAT" in state_by_seat:
        expected_cancel_race_state = (
            "CONFIRMED"
            if any(
                outcome.status == 201
                and outcome.body is not None
                and "CANCEL-SEAT" in outcome.body.get("seats", [])
                and outcome.body.get("reservation_id") not in cancelled_reservation_ids
                for outcome in all_outcomes
            )
            else "AVAILABLE"
        )
        if state_by_seat["CANCEL-SEAT"] != expected_cancel_race_state:
            failures.append(
                "Cancellation race seat has an invalid final state: "
                f"expected {expected_cancel_race_state}, got {state_by_seat['CANCEL-SEAT']}"
            )
    if len(seat_states) != total:
        failures.append(f"Seat list has {len(seat_states)} entries but total_seats is {total}")

    limit_user_seats = {
        seat
        for outcome in limit_successes
        if outcome.body is not None
        for seat in outcome.body.get("seats", [])
        if isinstance(seat, str)
    }
    if any(
        outcome.body is None or outcome.body.get("user_id") != limited_user
        for outcome in limit_successes
    ):
        failures.append("Per-user limit burst returned a reservation for the wrong user")
    if len(limit_user_seats) > args.per_user_limit:
        failures.append(
            f"Final response state shows {len(limit_user_seats)} seats for the limited user "
            f"(limit {args.per_user_limit})"
        )

    print(
        f"Final show state: total={total}, available={available}, held={held}, "
        f"confirmed={confirmed}; unique confirmed seats={len(confirmed_in_state)}."
    )
    if failures:
        print("FAILED invariants:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("PASS: concurrency and state invariants hold.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
