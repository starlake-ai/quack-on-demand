#!/usr/bin/env python3
"""
FlightSQL load tester for quack-on-demand.

Spawns N worker threads. Each opens its own ADBC connection (one handshake
against the edge, then reuses the bound session) and issues `iterations`
queries from the pool. Per-call latency feeds a shared list; at the end
we report throughput, success rate, and latency percentiles.

Dependencies:
    pip install adbc_driver_flightsql adbc_driver_manager

Usage:
    ./scripts/loadtest/loadtest.py                          # defaults: 8 x 100
    ./scripts/loadtest/loadtest.py -w 32 -i 500
    LT_QUERY='SELECT 1' ./scripts/loadtest/loadtest.py
    ./scripts/loadtest/loadtest.py --url grpc+tls://localhost:31338 --insecure
"""
from __future__ import annotations

import argparse
import os
import statistics
import sys
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List


# Default workload: a curated TPCH-H subset. These are the canonical
# benchmark queries (Q1, Q3, Q5, Q6, Q10, Q12, Q14) with the standard
# parameter values from the spec — see https://www.tpc.org/tpch/. They
# exercise a variety of plan shapes: per-group aggregation, 3- to 6-way
# joins, lineitem filtering, CASE expressions, ORDER BY + LIMIT.
DEFAULT_QUERIES = [
    # --- Q1: Pricing Summary Report ---
    """SELECT l_returnflag, l_linestatus,
              sum(l_quantity)       AS sum_qty,
              sum(l_extendedprice)  AS sum_base_price,
              sum(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
              sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge,
              avg(l_quantity)       AS avg_qty,
              avg(l_extendedprice)  AS avg_price,
              avg(l_discount)       AS avg_disc,
              count(*)              AS count_order
       FROM lineitem
       WHERE l_shipdate <= DATE '1998-09-02'
       GROUP BY l_returnflag, l_linestatus
       ORDER BY l_returnflag, l_linestatus""",

    # --- Q3: Shipping Priority (3-way join + group + top-N) ---
    """SELECT l_orderkey,
              sum(l_extendedprice * (1 - l_discount)) AS revenue,
              o_orderdate,
              o_shippriority
       FROM customer, orders, lineitem
       WHERE c_mktsegment = 'BUILDING'
         AND c_custkey  = o_custkey
         AND l_orderkey = o_orderkey
         AND o_orderdate < DATE '1995-03-15'
         AND l_shipdate  > DATE '1995-03-15'
       GROUP BY l_orderkey, o_orderdate, o_shippriority
       ORDER BY revenue DESC, o_orderdate
       LIMIT 10""",

    # --- Q5: Local Supplier Volume (6-way join) ---
    """SELECT n_name,
              sum(l_extendedprice * (1 - l_discount)) AS revenue
       FROM customer, orders, lineitem, supplier, nation, region
       WHERE c_custkey    = o_custkey
         AND l_orderkey   = o_orderkey
         AND l_suppkey    = s_suppkey
         AND c_nationkey  = s_nationkey
         AND s_nationkey  = n_nationkey
         AND n_regionkey  = r_regionkey
         AND r_name       = 'ASIA'
         AND o_orderdate >= DATE '1994-01-01'
         AND o_orderdate  < DATE '1995-01-01'
       GROUP BY n_name
       ORDER BY revenue DESC""",

    # --- Q6: Forecasting Revenue (lineitem filter + sum) ---
    """SELECT sum(l_extendedprice * l_discount) AS revenue
       FROM lineitem
       WHERE l_shipdate >= DATE '1994-01-01'
         AND l_shipdate  < DATE '1995-01-01'
         AND l_discount BETWEEN 0.05 AND 0.07
         AND l_quantity  < 24""",

    # --- Q10: Returned Item Reporting ---
    """SELECT c_custkey, c_name,
              sum(l_extendedprice * (1 - l_discount)) AS revenue,
              c_acctbal, n_name, c_address, c_phone, c_comment
       FROM customer, orders, lineitem, nation
       WHERE c_custkey   = o_custkey
         AND l_orderkey  = o_orderkey
         AND o_orderdate >= DATE '1993-10-01'
         AND o_orderdate  < DATE '1994-01-01'
         AND l_returnflag = 'R'
         AND c_nationkey  = n_nationkey
       GROUP BY c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment
       ORDER BY revenue DESC
       LIMIT 20""",

    # --- Q12: Shipping Modes and Order Priority ---
    """SELECT l_shipmode,
              sum(CASE WHEN o_orderpriority = '1-URGENT'
                       OR o_orderpriority = '2-HIGH' THEN 1 ELSE 0 END) AS high_line_count,
              sum(CASE WHEN o_orderpriority <> '1-URGENT'
                       AND o_orderpriority <> '2-HIGH' THEN 1 ELSE 0 END) AS low_line_count
       FROM orders, lineitem
       WHERE o_orderkey      = l_orderkey
         AND l_shipmode IN ('MAIL', 'SHIP')
         AND l_commitdate    < l_receiptdate
         AND l_shipdate      < l_commitdate
         AND l_receiptdate  >= DATE '1994-01-01'
         AND l_receiptdate   < DATE '1995-01-01'
       GROUP BY l_shipmode
       ORDER BY l_shipmode""",

    # --- Q14: Promotion Effect ---
    """SELECT 100.00 * sum(CASE WHEN p_type LIKE 'PROMO%'
                                 THEN l_extendedprice * (1 - l_discount)
                                 ELSE 0 END)
              / sum(l_extendedprice * (1 - l_discount)) AS promo_revenue
       FROM lineitem, part
       WHERE l_partkey   = p_partkey
         AND l_shipdate >= DATE '1995-09-01'
         AND l_shipdate  < DATE '1995-10-01'""",
]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="FlightSQL load tester for quack-on-demand",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--url",
                   default=os.environ.get("LT_URL", "grpc+tls://localhost:31338"),
                   help="ADBC FlightSQL URL (grpc+tls:// or grpc://)")
    p.add_argument("-u", "--user",
                   default=os.environ.get("LT_USER", "admin"))
    p.add_argument("-p", "--password",
                   default=os.environ.get("LT_PASSWORD", "admin"))
    p.add_argument("-w", "--workers", type=int,
                   default=int(os.environ.get("LT_WORKERS", "8")))
    p.add_argument("-i", "--iterations", type=int,
                   default=int(os.environ.get("LT_ITERATIONS", "100")),
                   help="queries per worker")
    p.add_argument("--warmup", type=int,
                   default=int(os.environ.get("LT_WARMUP", "5")),
                   help="throwaway iterations per worker before counting")
    p.add_argument("-q", "--query",
                   default=os.environ.get("LT_QUERY"),
                   help="single SQL to repeat (else cycles the default mix)")
    p.add_argument("--insecure", action="store_true",
                   default=os.environ.get("LT_INSECURE", "true").lower() == "true",
                   help="skip TLS certificate verification (dev only)")
    return p.parse_args()


def connect(args: argparse.Namespace):
    """Open a single ADBC connection. Imports inside the function so the
    --help path doesn't require the adbc packages."""
    try:
        import adbc_driver_flightsql.dbapi as flight_sql
        from adbc_driver_flightsql import DatabaseOptions
    except ImportError as e:
        print("ERROR: adbc_driver_flightsql not installed.", file=sys.stderr)
        print("       pip install adbc_driver_flightsql adbc_driver_manager", file=sys.stderr)
        raise SystemExit(2) from e

    db_kwargs = {
        DatabaseOptions.AUTHORIZATION_HEADER.value: None,  # unused — we pass user/pass
        "username": args.user,
        "password": args.password,
    }
    # ADBC's option for "trust any cert" is the tls_skip_verify db_kwarg.
    if args.insecure and args.url.startswith("grpc+tls"):
        db_kwargs[DatabaseOptions.TLS_SKIP_VERIFY.value] = "true"
    # Strip the placeholder None key (set above for clarity)
    db_kwargs = {k: v for k, v in db_kwargs.items() if v is not None}

    return flight_sql.connect(uri=args.url, db_kwargs=db_kwargs)


def run_worker(
    wid: int,
    args: argparse.Namespace,
    queries: List[str],
    latencies_ms: List[float],
    err_counter: Counter[str],
    err_lock: threading.Lock,
) -> tuple[int, int]:
    """Run one worker. Returns (success, error) counts."""
    ok, err = 0, 0
    try:
        conn = connect(args)
    except Exception as e:
        with err_lock:
            err_counter[f"connect: {first_line(str(e))}"] += args.iterations
        return 0, args.iterations

    try:
        local_lat: List[float] = []
        with conn.cursor() as cur:
            for i in range(args.warmup):
                try:
                    sql = queries[(i + wid) % len(queries)]
                    cur.execute(sql)
                    cur.fetchall()
                except Exception:
                    pass  # warmup failures don't count
            for i in range(args.iterations):
                sql = queries[(i + wid) % len(queries)]
                t0 = time.perf_counter()
                try:
                    cur.execute(sql)
                    cur.fetchall()
                    local_lat.append((time.perf_counter() - t0) * 1000.0)
                    ok += 1
                except Exception as e:
                    err += 1
                    msg = first_line(str(e))
                    with err_lock:
                        err_counter[msg] += 1
        # Single shared append at end to minimise list contention.
        latencies_ms.extend(local_lat)
    finally:
        try:
            conn.close()
        except Exception:
            pass
    return ok, err


def first_line(s: str) -> str:
    if not s:
        return "(no message)"
    return s.splitlines()[0]


def pct(sorted_values: List[float], p: float) -> float:
    """Nearest-rank percentile. Sorted_values must be ascending and non-empty."""
    if not sorted_values:
        return 0.0
    idx = min(len(sorted_values) - 1, max(0, round(p * (len(sorted_values) - 1))))
    return sorted_values[idx]


def main() -> int:
    args = parse_args()
    queries = [args.query] if args.query else DEFAULT_QUERIES

    print(
        f"Load test: {args.workers} workers x {args.iterations} iterations "
        f"(+{args.warmup} warmup) against {args.url} as {args.user}"
    )

    latencies_ms: List[float] = []
    err_counter: Counter[str] = Counter()
    err_lock = threading.Lock()

    t0_wall = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [
            pool.submit(run_worker, w, args, queries, latencies_ms, err_counter, err_lock)
            for w in range(args.workers)
        ]
        totals = [f.result() for f in as_completed(futures)]
    elapsed_ms = (time.perf_counter() - t0_wall) * 1000.0

    success = sum(ok for ok, _ in totals)
    errors  = sum(er for _, er in totals)

    print()
    print("─── Results ───")
    print(f"Wall time:        {elapsed_ms:.0f} ms ({elapsed_ms / 1000:.2f} s)")
    print(f"Queries OK:       {success}")
    print(f"Queries failed:   {errors}")
    if success + errors > 0:
        print(f"Throughput:       {(success + errors) * 1000.0 / max(elapsed_ms, 1):.1f} qps (overall)")
    if latencies_ms:
        s = sorted(latencies_ms)
        print(f"Latency  min:    {s[0]:.1f} ms")
        print(f"         p50:    {pct(s, 0.50):.1f} ms")
        print(f"         p95:    {pct(s, 0.95):.1f} ms")
        print(f"         p99:    {pct(s, 0.99):.1f} ms")
        print(f"         max:    {s[-1]:.1f} ms")
        print(f"         mean:   {statistics.fmean(s):.1f} ms")
    if errors:
        print()
        print("─── Errors (by first line, top 10) ───")
        for msg, n in err_counter.most_common(10):
            print(f"  {n:5d} × {msg}")

    return 2 if errors > 0 else 0


if __name__ == "__main__":
    sys.exit(main())
