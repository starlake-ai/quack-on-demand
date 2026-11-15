#!/usr/bin/env python3
"""The 10M (and 100M) input drain through Flight SQL (QoD Search Phase 0 part 2, plan Task 5;
spec 7.6, 7.4).

Usage:
  uv run --no-project --with adbc-driver-flightsql --with pyarrow \
    python -I drain.py <p2> [--host H] [--port P] [--results-dir D] [--sizes 10m,100m_drain]
                       [--dry-run]

As the tenant user `plain` (password read from <p2>/secrets/plain.txt, never printed), on one
reused ADBC connection to the part 2 stack's Flight SQL edge (TLS, the edge's self-signed
certificate not verified, as the qod CLI does; tenant `acme` and pool `bi` as call headers: Q12's
pattern), with stopping.run_until_stable (section 11's rule, as in P4):
- the tenant floor `SELECT 1`, first and last (floor_p50_ms is the mean of the two p50);
- the 10-rowid fetch at 10M, `SELECT id, title FROM acme_lake.main.pages_10m WHERE rowid IN (...)`,
  10 random rowids drawn afresh for every execution (Q13's shape through the gateway), 10 rows;
- per size (pages_10m, pages_100m_drain) and input (no filter, f10 = 0, f100 = 0), the three
  statements of the node-local bench (search_drain_bench.cpp):
    drain     SELECT "rows", max_rowid, drain_ms FROM qod_search_drain((<input>))
    parallel  SELECT sum("rows") FROM qod_search_drain_parallel((<input>))
    count     SELECT count(*) FROM (<input>)
  with <input> = SELECT rowid FROM acme_lake.main.pages_<n> [WHERE f10 = 0 | WHERE f100 = 0]. The
  `WHERE` inputs only: the drain needs no search, so no row-policy wrap.
Each execution is `cursor.execute(sql); cursor.fetchall()`, timed with time.perf_counter_ns. Every
result is checked (row counts as expected; the fetch returns 10 rows); a mismatch or an error
stops the run with a non-zero exit. Warm-ups: 20 per statement (5 at 100M); batches of 200 (20 at
100M, whose single-threaded drain takes about 0.4 s a run), at most 10. One JSON object per
statement is appended to D/drain.jsonl once the last floor is measured (default D is
<p2>/results) and each is printed as it completes.

--dry-run: 2 warm-ups, batches of 5, at most 3 batches, objects tagged "dry_run": true and written
to D/drain-dryrun.jsonl (default D <p2>/results-dryrun). A check of the runner, never evidence.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import argparse  # noqa: E402
import json  # noqa: E402
import random  # noqa: E402
import statistics  # noqa: E402
import time  # noqa: E402

import adbc_driver_flightsql.dbapi as flight  # noqa: E402
from adbc_driver_flightsql import DatabaseOptions  # noqa: E402

from stopping import percentile, run_until_stable  # noqa: E402

CATALOG = "acme_lake"
TENANT, POOL, USER = "acme", "bi", "plain"
FILTERS = {"100%": "", "10%": " WHERE f10 = 0", "1%": " WHERE f100 = 0"}
SIZE_ROWS = {"1m": 1_000_000, "10m": 10_000_000, "100m_drain": 100_000_000}
FRACTION = {"100%": 1, "10%": 10, "1%": 100}
HDR = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value  # adbc.flight.sql.rpc.call_header.


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("p2")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27338)
    ap.add_argument("--results-dir")
    ap.add_argument("--sizes", default="10m,100m_drain")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    a.results_dir = a.results_dir or os.path.join(a.p2, "results-dryrun" if a.dry_run else "results")
    return a


def connect(args):
    with open(os.path.join(args.p2, "secrets", "plain.txt"), encoding="utf-8") as f:
        password = f.read().strip()
    db_kwargs = {
        "username": USER,
        "password": password,
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
        HDR + "tenant": TENANT,
        HDR + "pool": POOL,
    }
    return flight.connect(f"grpc+tls://{args.host}:{args.port}", db_kwargs=db_kwargs, autocommit=True)


class Check(Exception):
    pass


def timer(cur, make_sql, check, extra):
    """batch(n): n executions of make_sql(), each result passed to check; extra collects what check
    returns besides the latency (the drain's own drain_ms)."""
    def batch(n):
        lat = []
        for _ in range(n):
            sql = make_sql()
            t0 = time.perf_counter_ns()
            cur.execute(sql)
            rows = cur.fetchall()
            t1 = time.perf_counter_ns()
            lat.append((t1 - t0) / 1e6)
            value = check(rows)
            if value is not None:
                extra.append(value)
        return lat
    return batch


def measure(cur, make_sql, check, warmup, batch_size, max_batches):
    for _ in range(warmup):
        cur.execute(make_sql())
        check(cur.fetchall())
    extra = []
    samples, batches = run_until_stable(timer(cur, make_sql, check, extra), batch_size, max_batches)
    out = {
        "p50_ms": round(percentile(samples, 0.50), 4),
        "p95_ms": round(percentile(samples, 0.95), 4),
        "p99_ms": round(percentile(samples, 0.99), 4),
        "mean_ms": round(statistics.mean(samples), 4),
        "batches": batches,
        "samples": len(samples),
    }
    if extra:
        out["drain_ms_p50"] = round(percentile(extra, 0.50), 4)
    return out


def expect_rows(n_rows, expected):
    def check(rows):
        if len(rows) != n_rows:
            raise Check(f"expected {n_rows} result rows, got {len(rows)}")
        if expected is not None and rows[0][0] != expected:
            raise Check(f"expected {expected}, got {rows[0][0]}")
        return None
    return check


def drain_check(expected):
    def check(rows):
        if len(rows) != 1 or rows[0][0] != expected:
            raise Check(f"expected one row with rows = {expected}, got {rows[:2]}")
        return float(rows[0][2])
    return check


def main():
    args = parse_args()
    if args.dry_run:
        warm, warm_big, size, size_big, max_b = 2, 2, 5, 5, 3
    else:
        warm, warm_big, size, size_big, max_b = 20, 5, 200, 20, 10
    sizes = [s for s in args.sizes.split(",") if s]
    os.makedirs(args.results_dir, exist_ok=True)
    out_path = os.path.join(args.results_dir, "drain-dryrun.jsonl" if args.dry_run else "drain.jsonl")
    rng = random.Random(20261114)
    objects = []

    def record(obj):
        obj["dry_run"] = args.dry_run
        objects.append(obj)
        print(json.dumps(obj), flush=True)

    with connect(args) as conn:
        cur = conn.cursor()
        floors = []

        def floor(position):
            r = measure(cur, lambda: "SELECT 1", expect_rows(1, 1), warm, size, max_b)
            floors.append(r["p50_ms"])
            record({"kind": "floor", "position": position, "sql": "SELECT 1", **r})

        floor("first")
        if "10m" in sizes:
            table = f"{CATALOG}.main.pages_10m"

            def fetch_sql():
                ids = ", ".join(str(i) for i in rng.sample(range(SIZE_ROWS["10m"]), 10))
                return f"SELECT id, title FROM {table} WHERE rowid IN ({ids})"
            r = measure(cur, fetch_sql, expect_rows(10, None), warm, size, max_b)
            record({"kind": "fetch", "size": "10m",
                    "sql": f"SELECT id, title FROM {table} WHERE rowid IN (<10 random rowids>)", **r})
        for s in sizes:
            table = f"{CATALOG}.main.pages_{s}"
            big = s == "100m_drain"
            for name, filt in FILTERS.items():
                inp = f"SELECT rowid FROM {table}{filt}"
                expected = SIZE_ROWS[s] // FRACTION[name]
                statements = [
                    ("drain", f'SELECT "rows", max_rowid, drain_ms FROM qod_search_drain(({inp}))',
                     drain_check(expected)),
                    ("parallel", f'SELECT sum("rows") FROM qod_search_drain_parallel(({inp}))',
                     expect_rows(1, expected)),
                    ("count", f"SELECT count(*) FROM ({inp})", expect_rows(1, expected)),
                ]
                for kind, sql, check in statements:
                    r = measure(cur, lambda sql=sql: sql, check, warm_big if big else warm,
                                size_big if big else size, max_b)
                    record({"kind": kind, "size": s, "input": name, "sql": sql, "rows": expected, **r})
        floor("last")
        floor_p50 = round(statistics.mean(floors), 4)
        cur.close()
    with open(out_path, "a", encoding="utf-8") as f:
        for obj in objects:
            obj["floor_p50_ms"] = floor_p50
            f.write(json.dumps(obj) + "\n")
    print(f"wrote {len(objects)} objects to {out_path}; floor p50 {floor_p50} ms", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Check as exc:
        print(f"CHECK FAILED: {exc}", flush=True)
        sys.exit(1)
