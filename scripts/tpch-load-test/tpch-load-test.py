#!/usr/bin/env python3
"""
FlightSQL load tester for quack-on-demand.

Spawns N worker threads. Each opens its own ADBC connection (one handshake
against the edge, then reuses the bound session) and issues `iterations`
queries from the pool. Per-call latency feeds a shared list; at the end
we report throughput, success rate, and latency percentiles.

Dependencies:
    pip install adbc_driver_flightsql adbc_driver_manager

Usage (tenant + pool are required; pass them or set LT_TENANT / LT_POOL):
    ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi
    ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi -w 32 -i 500
    LT_QUERY='SELECT 1' ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi
    ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi \
        --url grpc+tls://localhost:31338 --insecure
    ./scripts/tpch-load-test/tpch-load-test.py --tenant acme --pool bi --superuser

    # TPC-DS workload against the globex demo (schema=tpcds1, populated by
    # scripts/load-tpcds-dbgen.sh):
    ./scripts/tpch-load-test/tpch-load-test.py --workload tpcds --tenant globex --pool bi
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
# parameter values from the spec - see https://www.tpc.org/tpch/. They
# exercise a variety of plan shapes: per-group aggregation, 3- to 6-way
# joins, lineitem filtering, CASE expressions, ORDER BY + LIMIT.
#
# Each table reference is prefixed at runtime with the schema chosen via
# --schema / $LT_SCHEMA so the queries resolve regardless of what the
# session's default schema happens to be. This lets the load tester point
# at TPC-H seeded into tpch.tpch1.* without the server having to default
# its session schema there.
def default_queries_tpch(schema: str) -> List[str]:
    s = schema
    return [
        # --- Q1: Pricing Summary Report ---
        f"""SELECT l_returnflag, l_linestatus,
                  sum(l_quantity)       AS sum_qty,
                  sum(l_extendedprice)  AS sum_base_price,
                  sum(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
                  sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge,
                  avg(l_quantity)       AS avg_qty,
                  avg(l_extendedprice)  AS avg_price,
                  avg(l_discount)       AS avg_disc,
                  count(*)              AS count_order
           FROM {s}.lineitem
           WHERE l_shipdate <= DATE '1998-09-02'
           GROUP BY l_returnflag, l_linestatus
           ORDER BY l_returnflag, l_linestatus""",

        # --- Q3: Shipping Priority (3-way join + group + top-N) ---
        f"""SELECT l_orderkey,
                  sum(l_extendedprice * (1 - l_discount)) AS revenue,
                  o_orderdate,
                  o_shippriority
           FROM {s}.customer, {s}.orders, {s}.lineitem
           WHERE c_mktsegment = 'BUILDING'
             AND c_custkey  = o_custkey
             AND l_orderkey = o_orderkey
             AND o_orderdate < DATE '1995-03-15'
             AND l_shipdate  > DATE '1995-03-15'
           GROUP BY l_orderkey, o_orderdate, o_shippriority
           ORDER BY revenue DESC, o_orderdate
           LIMIT 10""",

        # --- Q5: Local Supplier Volume (6-way join) ---
        f"""SELECT n_name,
                  sum(l_extendedprice * (1 - l_discount)) AS revenue
           FROM {s}.customer, {s}.orders, {s}.lineitem, {s}.supplier, {s}.nation, {s}.region
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
        f"""SELECT sum(l_extendedprice * l_discount) AS revenue
           FROM {s}.lineitem
           WHERE l_shipdate >= DATE '1994-01-01'
             AND l_shipdate  < DATE '1995-01-01'
             AND l_discount BETWEEN 0.05 AND 0.07
             AND l_quantity  < 24""",

        # --- Q10: Returned Item Reporting ---
        f"""SELECT c_custkey, c_name,
                  sum(l_extendedprice * (1 - l_discount)) AS revenue,
                  c_acctbal, n_name, c_address, c_phone, c_comment
           FROM {s}.customer, {s}.orders, {s}.lineitem, {s}.nation
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
        f"""SELECT l_shipmode,
                  sum(CASE WHEN o_orderpriority = '1-URGENT'
                           OR o_orderpriority = '2-HIGH' THEN 1 ELSE 0 END) AS high_line_count,
                  sum(CASE WHEN o_orderpriority <> '1-URGENT'
                           AND o_orderpriority <> '2-HIGH' THEN 1 ELSE 0 END) AS low_line_count
           FROM {s}.orders, {s}.lineitem
           WHERE o_orderkey      = l_orderkey
             AND l_shipmode IN ('MAIL', 'SHIP')
             AND l_commitdate    < l_receiptdate
             AND l_shipdate      < l_commitdate
             AND l_receiptdate  >= DATE '1994-01-01'
             AND l_receiptdate   < DATE '1995-01-01'
           GROUP BY l_shipmode
           ORDER BY l_shipmode""",

        # --- Q14: Promotion Effect ---
        f"""SELECT 100.00 * sum(CASE WHEN p_type LIKE 'PROMO%'
                                     THEN l_extendedprice * (1 - l_discount)
                                     ELSE 0 END)
                  / sum(l_extendedprice * (1 - l_discount)) AS promo_revenue
           FROM {s}.lineitem, {s}.part
           WHERE l_partkey   = p_partkey
             AND l_shipdate >= DATE '1995-09-01'
             AND l_shipdate  < DATE '1995-10-01'""",
    ]


# Default TPC-DS workload: a curated, varied-shape subset of the 99-query
# benchmark. Picked to exercise the same plan-shape range as the TPC-H mix
# (per-group aggregation, multi-way joins, top-N, CASE expressions, window
# functions, date-range filters) against the standard 24 TPC-DS tables that
# scripts/load-tpcds-dbgen.sh seeds into `globex_tpcds.tpcds1.*`.
#
# Queries match the official spec at https://www.tpc.org/tpc_documents_current_versions/pdf/tpc-ds_v3.2.0.pdf
# but use static parameter values (no RngenerateData step) for reproducibility.
# Each table reference is schema-prefixed at runtime via --schema / $LT_SCHEMA.
def default_queries_tpcds(schema: str) -> List[str]:
    s = schema
    return [
        # --- Q3: store_sales x date_dim x item, monthly-brand revenue rollup ---
        f"""SELECT d_year, i_brand_id AS brand_id, i_brand AS brand,
                  sum(ss_ext_sales_price) AS sum_agg
           FROM {s}.date_dim, {s}.store_sales, {s}.item
           WHERE d_date_sk    = ss_sold_date_sk
             AND ss_item_sk   = i_item_sk
             AND i_manufact_id = 128
             AND d_moy         = 11
           GROUP BY d_year, i_brand, i_brand_id
           ORDER BY d_year, sum_agg DESC, brand_id
           LIMIT 100""",

        # --- Q7: 5-way join with demographics + promotion filter ---
        f"""SELECT i_item_id,
                  avg(ss_quantity)        AS agg1,
                  avg(ss_list_price)      AS agg2,
                  avg(ss_coupon_amt)      AS agg3,
                  avg(ss_sales_price)     AS agg4
           FROM {s}.store_sales, {s}.customer_demographics, {s}.date_dim,
                {s}.item, {s}.promotion
           WHERE ss_sold_date_sk = d_date_sk
             AND ss_item_sk      = i_item_sk
             AND ss_cdemo_sk     = cd_demo_sk
             AND ss_promo_sk     = p_promo_sk
             AND cd_gender         = 'M'
             AND cd_marital_status = 'S'
             AND cd_education_status = 'College'
             AND (p_channel_email = 'N' OR p_channel_event = 'N')
             AND d_year = 2000
           GROUP BY i_item_id
           ORDER BY i_item_id
           LIMIT 100""",

        # --- Q19: 6-way join, group + top-N ---
        f"""SELECT i_brand_id AS brand_id, i_brand AS brand,
                  i_manufact_id, i_manufact,
                  sum(ss_ext_sales_price) AS ext_price
           FROM {s}.date_dim, {s}.store_sales, {s}.item,
                {s}.customer, {s}.customer_address, {s}.store
           WHERE d_date_sk    = ss_sold_date_sk
             AND ss_item_sk   = i_item_sk
             AND i_manager_id = 8
             AND d_moy        = 11
             AND d_year       = 1998
             AND ss_customer_sk = c_customer_sk
             AND c_current_addr_sk = ca_address_sk
             AND substr(ca_zip, 1, 5) <> substr(s_zip, 1, 5)
             AND ss_store_sk  = s_store_sk
           GROUP BY i_brand, i_brand_id, i_manufact_id, i_manufact
           ORDER BY ext_price DESC, i_brand, i_brand_id, i_manufact_id, i_manufact
           LIMIT 100""",

        # --- Q42: store_sales filter + 3-way join + small group ---
        f"""SELECT dt.d_year, item.i_category_id, item.i_category,
                  sum(ss_ext_sales_price) AS rev
           FROM {s}.date_dim dt, {s}.store_sales, {s}.item
           WHERE dt.d_date_sk    = store_sales.ss_sold_date_sk
             AND store_sales.ss_item_sk = item.i_item_sk
             AND item.i_manager_id  = 1
             AND dt.d_moy           = 11
             AND dt.d_year          = 2000
           GROUP BY dt.d_year, item.i_category_id, item.i_category
           ORDER BY rev DESC, dt.d_year, item.i_category_id, item.i_category
           LIMIT 100""",

        # --- Q52: brand-level revenue rollup, same shape family as Q42 ---
        f"""SELECT dt.d_year, item.i_brand_id AS brand_id, item.i_brand AS brand,
                  sum(ss_ext_sales_price) AS ext_price
           FROM {s}.date_dim dt, {s}.store_sales, {s}.item
           WHERE dt.d_date_sk    = store_sales.ss_sold_date_sk
             AND store_sales.ss_item_sk = item.i_item_sk
             AND item.i_manager_id  = 1
             AND dt.d_moy           = 11
             AND dt.d_year          = 2000
           GROUP BY dt.d_year, item.i_brand, item.i_brand_id
           ORDER BY dt.d_year, ext_price DESC, brand_id
           LIMIT 100""",

        # --- Q55: small 3-way join, brand top-N ---
        f"""SELECT i_brand_id AS brand_id, i_brand AS brand,
                  sum(ss_ext_sales_price) AS ext_price
           FROM {s}.date_dim, {s}.store_sales, {s}.item
           WHERE d_date_sk    = ss_sold_date_sk
             AND ss_item_sk   = i_item_sk
             AND i_manager_id = 28
             AND d_moy        = 11
             AND d_year       = 1999
           GROUP BY i_brand, i_brand_id
           ORDER BY ext_price DESC, i_brand_id
           LIMIT 100""",

        # --- Q98: per-class share-of-revenue with a window function ---
        f"""SELECT i_item_id, i_item_desc, i_category, i_class, i_current_price,
                  sum(ss_ext_sales_price) AS itemrevenue,
                  sum(ss_ext_sales_price) * 100.0
                    / sum(sum(ss_ext_sales_price)) OVER (PARTITION BY i_class)
                    AS revenueratio
           FROM {s}.store_sales, {s}.item, {s}.date_dim
           WHERE ss_item_sk = i_item_sk
             AND i_category IN ('Sports', 'Books', 'Home')
             AND ss_sold_date_sk = d_date_sk
             AND d_date BETWEEN DATE '1999-02-22'
                            AND DATE '1999-02-22' + INTERVAL '30' DAY
           GROUP BY i_item_id, i_item_desc, i_category, i_class, i_current_price
           ORDER BY i_category, i_class, i_item_id, i_item_desc, revenueratio""",
    ]


WORKLOADS = {
    "tpch":  ("tpch1",  default_queries_tpch),
    "tpcds": ("tpcds1", default_queries_tpcds),
}


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
                   help="single SQL to repeat (else cycles the workload mix)")
    p.add_argument("--workload",
                   choices=sorted(WORKLOADS.keys()),
                   default=os.environ.get("LT_WORKLOAD", "tpch"),
                   help="which curated benchmark to cycle (ignored with -q). "
                        "Each workload also sets a sensible default --schema "
                        "(tpch1 / tpcds1) unless --schema is given explicitly.")
    p.add_argument("--schema",
                   default=os.environ.get("LT_SCHEMA"),
                   help="schema to prefix the workload queries with (ignored "
                        "with -q). Defaults to the workload's canonical "
                        "schema (tpch1 for tpch, tpcds1 for tpcds).")
    p.add_argument("--tenant",
                   required=not os.environ.get("LT_TENANT"),
                   default=os.environ.get("LT_TENANT"),
                   help="tenant gRPC header for FlightSQL routing (required; "
                        "or set LT_TENANT)")
    p.add_argument("--pool",
                   required=not os.environ.get("LT_POOL"),
                   default=os.environ.get("LT_POOL"),
                   help="pool gRPC header for FlightSQL routing (required; "
                        "or set LT_POOL)")
    p.add_argument("--superuser", action="store_true",
                   default=os.environ.get("LT_SUPERUSER", "false").lower() == "true",
                   help="add the `superuser=true` gRPC header so the user is "
                        "authenticated against the system realm (qodstate_user "
                        "rows with tenant IS NULL); tenant/pool still drive "
                        "query routing. Required for the bootstrap admin user")
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
        DatabaseOptions.AUTHORIZATION_HEADER.value: None,  # unused - we pass user/pass
        "username": args.user,
        "password": args.password,
    }
    # ADBC's option for "trust any cert" is the tls_skip_verify db_kwarg.
    if args.insecure and args.url.startswith("grpc+tls"):
        db_kwargs[DatabaseOptions.TLS_SKIP_VERIFY.value] = "true"
    # FlightSQL routing: the edge's TenantSelector requires `tenant` + `pool`
    # gRPC headers on the Basic-auth path (superusers included), so the
    # router can pick the right (tenant, pool) of Quack nodes. Translate
    # into ADBC's per-RPC call-header db_kwarg.
    hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
    db_kwargs[hdr + "tenant"] = args.tenant
    db_kwargs[hdr + "pool"]   = args.pool
    # `superuser=true` switches the auth realm to the system realm so the
    # edge looks up the user in qodstate_user rows with tenant IS NULL.
    # tenant/pool still drive query routing.
    if args.superuser:
        db_kwargs[hdr + "superuser"] = "true"
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
    default_schema, builder = WORKLOADS[args.workload]
    schema = args.schema or default_schema
    queries = [args.query] if args.query else builder(schema)
    mix_label = (
        "custom -q" if args.query
        else f"default {args.workload.upper()} mix [schema={schema}]"
    )

    scope = f"{args.tenant}/{args.pool}"
    realm = "system" if args.superuser else "tenant"
    print(
        f"Load test: {args.workers} workers x {args.iterations} iterations "
        f"(+{args.warmup} warmup) against {args.url} as {args.user} -> {scope} ({realm} realm)"
    )
    print(f"Workload:  {mix_label}")

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
