#!/usr/bin/env python3
"""Q12: per-statement latency floor through Flight SQL (ADBC) for two tenant users.

Usage: python3 -I q12_floor.py <host> <flight port> <tenant> <pool> <runs> [catalog]
Q12_FRESH=0 skips the fresh-connection runs.
Reads the two users' passwords from Q12_PLAIN_PASSWORD and Q12_POLICY_PASSWORD (never argv).
When Q12_ADMIN_PASSWORD is set, also measures the superuser `admin` (superuser=true header),
whose statements the node admits without a decide call: a reference for the authz cost.
Prints p50 and p95 in milliseconds per (user, statement, fresh or reused connection).

The catalog defaults to acme_lake: `qod serve --tenant acme --name lake` names the DuckDB
catalog after the composed tenant-db, not `lake`.

TLS: the edge's self-signed certificate is not verified (tls_skip_verify), as the qod CLI does;
the channel is still TLS.
"""
import math
import os
import statistics
import sys
import time

import adbc_driver_flightsql.dbapi as flight
from adbc_driver_flightsql import DatabaseOptions

host, port, tenant, pool, runs = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
catalog = sys.argv[6] if len(sys.argv) > 6 else "acme_lake"
fresh_runs = max(50, runs // 4)

users = {"plain": os.environ["Q12_PLAIN_PASSWORD"], "policy": os.environ["Q12_POLICY_PASSWORD"]}
if os.environ.get("Q12_ADMIN_PASSWORD"):
    users["admin"] = os.environ["Q12_ADMIN_PASSWORD"]

statements = {
    "select-1": "SELECT 1",
    "read-10": f"SELECT rowid FROM {catalog}.main.pages WHERE id < 10",
    "read-10-filtered": (
        f"SELECT rowid FROM {catalog}.main.pages WHERE id < 1000 AND title LIKE 't9%' LIMIT 10"
    ),
    # The row-policy rewrite reads the table through a subquery, which has no rowid column
    # (Binder Error: Referenced column "rowid" not found), so the same reads by id as well.
    "read-10-id": f"SELECT id FROM {catalog}.main.pages WHERE id < 10",
    "read-10-filtered-id": (
        f"SELECT id FROM {catalog}.main.pages WHERE id < 1000 AND title LIKE 't9%' LIMIT 10"
    ),
}

HDR = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value  # adbc.flight.sql.rpc.call_header.


def connect(user, password):
    db_kwargs = {
        "username": user,
        "password": password,
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
        HDR + "tenant": tenant,
        HDR + "pool": pool,
    }
    if user == "admin":
        db_kwargs[HDR + "superuser"] = "true"
    return flight.connect(f"grpc+tls://{host}:{port}", db_kwargs=db_kwargs, autocommit=True)


def pct(times):
    times = sorted(times)
    # p95: nearest rank, the ceil(0.95 * n)-th smallest (0-based index ceil(0.95 * n) - 1).
    return statistics.median(times), times[math.ceil(0.95 * len(times)) - 1]


def run_once(cur, sql):
    cur.execute(sql)
    return cur.fetchall()


def measure_reused(cur, sql):
    """Total per statement, and its first half: cursor.execute is the GetFlightInfo call (the
    gateway runs the statement's schema probe there), fetchall the DoGet that runs it."""
    times, execs = [], []
    for _ in range(runs):
        start = time.perf_counter()
        cur.execute(sql)
        mid = time.perf_counter()
        cur.fetchall()
        end = time.perf_counter()
        times.append((end - start) * 1000)
        execs.append((mid - start) * 1000)
    return pct(times) + pct(execs)


def measure_fresh(user, password, sql):
    """Connect (handshake included), run, close: what a client that does not pool pays."""
    times = []
    for _ in range(fresh_runs):
        start = time.perf_counter()
        with connect(user, password) as conn:
            cur = conn.cursor()
            run_once(cur, sql)
            cur.close()
        times.append((time.perf_counter() - start) * 1000)
    return pct(times)


print(f"runs={runs} (reused), {fresh_runs} (fresh), host={host}:{port}, tenant={tenant}, pool={pool}")
for user, password in users.items():
    with connect(user, password) as conn:
        cur = conn.cursor()
        for name, sql in statements.items():
            try:
                rows = run_once(cur, sql)  # warm-up and plan cache
                for _ in range(4):
                    run_once(cur, sql)
            except Exception as exc:  # noqa: BLE001 - a refusal is a finding
                print(f"{user:7s} {name:19s} REFUSED: {str(exc).splitlines()[0][:200]}")
                continue
            p50, p95, e50, e95 = measure_reused(cur, sql)
            print(
                f"{user:7s} {name:19s} rows={len(rows):2d} reused-connection "
                f"p50={p50:7.2f} ms p95={p95:7.2f} ms (GetFlightInfo part p50={e50:6.2f} "
                f"p95={e95:6.2f})"
            )
        cur.close()
    for name, sql in statements.items():
        if os.environ.get("Q12_FRESH", "1") == "0":
            break
        try:
            p50, p95 = measure_fresh(user, password, sql)
        except Exception as exc:  # noqa: BLE001
            print(f"{user:7s} {name:19s} fresh REFUSED: {str(exc).splitlines()[0][:200]}")
            continue
        print(f"{user:7s} {name:19s} fresh-connection  p50={p50:7.2f} ms p95={p95:7.2f} ms")
