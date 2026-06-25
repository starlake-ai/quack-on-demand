#!/usr/bin/env python3
"""
TPC-H 22-query benchmark for quack-on-demand.

Runs each of the 22 canonical TPC-H queries (Q1..Q22, standard spec
parameter values) once as a warmup, then `--runs` timed executions, and
reports the per-query median / min / max wall-clock latency. Results are
written as both CSV and a self-describing HTML report whose chart plots
query number (X) against median duration in milliseconds (Y).

Queries flow through the FlightSQL edge exactly like a real client:
    client -> FlightProducerImpl -> AuthenticationService -> FlightSqlRouter
    -> QuackHttpAdapter -> child quack node -> Arrow stream back.

The table names are schema-qualified (`{schema}.lineitem`, ...) so the
queries resolve regardless of the session's default schema. The default
target is the demo `acme` tenant, `bi` pool, `tpch1` schema (seeded by
scripts/load-tpch-dbgen.sh).

Dependencies (provisioned automatically by run.sh):
    pip install adbc_driver_flightsql adbc_driver_manager pyarrow

Usage:
    scripts/load-22/run.sh                       # all defaults (acme/bi/tpch1)
    scripts/load-22/run.sh --runs 10 --warmup 2
    scripts/load-22/run.sh --tenant globex --pool bi --schema tpch1
    scripts/load-22/run.sh --out-dir /tmp/tpch-bench
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import os
import statistics
import sys
import time
from typing import List, Optional


# ---------------------------------------------------------------------------
# The 22 canonical TPC-H queries, with the standard validation-substitution
# parameter values from the TPC-H spec (https://www.tpc.org/tpch/). Every
# table reference is qualified with the chosen schema so the queries resolve
# without relying on the session's default search path. Q15 (normally a
# CREATE VIEW) is inlined as a CTE so it needs no DDL / extra ACL grant.
# ---------------------------------------------------------------------------
def tpch_queries(schema: str) -> List[tuple[str, str]]:
    s = schema
    return [
        ("Q1", f"""
            SELECT l_returnflag, l_linestatus,
                   sum(l_quantity) AS sum_qty,
                   sum(l_extendedprice) AS sum_base_price,
                   sum(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
                   sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge,
                   avg(l_quantity) AS avg_qty, avg(l_extendedprice) AS avg_price,
                   avg(l_discount) AS avg_disc, count(*) AS count_order
            FROM {s}.lineitem
            WHERE l_shipdate <= DATE '1998-12-01' - INTERVAL '90' DAY
            GROUP BY l_returnflag, l_linestatus
            ORDER BY l_returnflag, l_linestatus"""),

        ("Q2", f"""
            SELECT s_acctbal, s_name, n_name, p_partkey, p_mfgr,
                   s_address, s_phone, s_comment
            FROM {s}.part, {s}.supplier, {s}.partsupp, {s}.nation, {s}.region
            WHERE p_partkey = ps_partkey AND s_suppkey = ps_suppkey AND p_size = 15
              AND p_type LIKE '%BRASS' AND s_nationkey = n_nationkey
              AND n_regionkey = r_regionkey AND r_name = 'EUROPE'
              AND ps_supplycost = (
                  SELECT min(ps_supplycost)
                  FROM {s}.partsupp, {s}.supplier, {s}.nation, {s}.region
                  WHERE p_partkey = ps_partkey AND s_suppkey = ps_suppkey
                    AND s_nationkey = n_nationkey AND n_regionkey = r_regionkey
                    AND r_name = 'EUROPE')
            ORDER BY s_acctbal DESC, n_name, s_name, p_partkey
            LIMIT 100"""),

        ("Q3", f"""
            SELECT l_orderkey,
                   sum(l_extendedprice * (1 - l_discount)) AS revenue,
                   o_orderdate, o_shippriority
            FROM {s}.customer, {s}.orders, {s}.lineitem
            WHERE c_mktsegment = 'BUILDING' AND c_custkey = o_custkey
              AND l_orderkey = o_orderkey AND o_orderdate < DATE '1995-03-15'
              AND l_shipdate > DATE '1995-03-15'
            GROUP BY l_orderkey, o_orderdate, o_shippriority
            ORDER BY revenue DESC, o_orderdate
            LIMIT 10"""),

        ("Q4", f"""
            SELECT o_orderpriority, count(*) AS order_count
            FROM {s}.orders
            WHERE o_orderdate >= DATE '1993-07-01'
              AND o_orderdate < DATE '1993-07-01' + INTERVAL '3' MONTH
              AND EXISTS (SELECT * FROM {s}.lineitem
                          WHERE l_orderkey = o_orderkey
                            AND l_commitdate < l_receiptdate)
            GROUP BY o_orderpriority
            ORDER BY o_orderpriority"""),

        ("Q5", f"""
            SELECT n_name, sum(l_extendedprice * (1 - l_discount)) AS revenue
            FROM {s}.customer, {s}.orders, {s}.lineitem, {s}.supplier,
                 {s}.nation, {s}.region
            WHERE c_custkey = o_custkey AND l_orderkey = o_orderkey
              AND l_suppkey = s_suppkey AND c_nationkey = s_nationkey
              AND s_nationkey = n_nationkey AND n_regionkey = r_regionkey
              AND r_name = 'ASIA' AND o_orderdate >= DATE '1994-01-01'
              AND o_orderdate < DATE '1994-01-01' + INTERVAL '1' YEAR
            GROUP BY n_name
            ORDER BY revenue DESC"""),

        ("Q6", f"""
            SELECT sum(l_extendedprice * l_discount) AS revenue
            FROM {s}.lineitem
            WHERE l_shipdate >= DATE '1994-01-01'
              AND l_shipdate < DATE '1994-01-01' + INTERVAL '1' YEAR
              AND l_discount BETWEEN 0.06 - 0.01 AND 0.06 + 0.01
              AND l_quantity < 24"""),

        ("Q7", f"""
            SELECT supp_nation, cust_nation, l_year, sum(volume) AS revenue
            FROM (
                SELECT n1.n_name AS supp_nation, n2.n_name AS cust_nation,
                       extract(year FROM l_shipdate) AS l_year,
                       l_extendedprice * (1 - l_discount) AS volume
                FROM {s}.supplier, {s}.lineitem, {s}.orders, {s}.customer,
                     {s}.nation n1, {s}.nation n2
                WHERE s_suppkey = l_suppkey AND o_orderkey = l_orderkey
                  AND c_custkey = o_custkey AND s_nationkey = n1.n_nationkey
                  AND c_nationkey = n2.n_nationkey
                  AND ((n1.n_name = 'FRANCE' AND n2.n_name = 'GERMANY')
                    OR (n1.n_name = 'GERMANY' AND n2.n_name = 'FRANCE'))
                  AND l_shipdate BETWEEN DATE '1995-01-01' AND DATE '1996-12-31'
            ) AS shipping
            GROUP BY supp_nation, cust_nation, l_year
            ORDER BY supp_nation, cust_nation, l_year"""),

        ("Q8", f"""
            SELECT o_year,
                   sum(CASE WHEN nation = 'BRAZIL' THEN volume ELSE 0 END)
                     / sum(volume) AS mkt_share
            FROM (
                SELECT extract(year FROM o_orderdate) AS o_year,
                       l_extendedprice * (1 - l_discount) AS volume,
                       n2.n_name AS nation
                FROM {s}.part, {s}.supplier, {s}.lineitem, {s}.orders,
                     {s}.customer, {s}.nation n1, {s}.nation n2, {s}.region
                WHERE p_partkey = l_partkey AND s_suppkey = l_suppkey
                  AND l_orderkey = o_orderkey AND o_custkey = c_custkey
                  AND c_nationkey = n1.n_nationkey AND n1.n_regionkey = r_regionkey
                  AND r_name = 'AMERICA' AND s_nationkey = n2.n_nationkey
                  AND o_orderdate BETWEEN DATE '1995-01-01' AND DATE '1996-12-31'
                  AND p_type = 'ECONOMY ANODIZED STEEL'
            ) AS all_nations
            GROUP BY o_year
            ORDER BY o_year"""),

        ("Q9", f"""
            SELECT nation, o_year, sum(amount) AS sum_profit
            FROM (
                SELECT n_name AS nation, extract(year FROM o_orderdate) AS o_year,
                       l_extendedprice * (1 - l_discount)
                         - ps_supplycost * l_quantity AS amount
                FROM {s}.part, {s}.supplier, {s}.lineitem, {s}.partsupp,
                     {s}.orders, {s}.nation
                WHERE s_suppkey = l_suppkey AND ps_suppkey = l_suppkey
                  AND ps_partkey = l_partkey AND p_partkey = l_partkey
                  AND o_orderkey = l_orderkey AND s_nationkey = n_nationkey
                  AND p_name LIKE '%green%'
            ) AS profit
            GROUP BY nation, o_year
            ORDER BY nation, o_year DESC"""),

        ("Q10", f"""
            SELECT c_custkey, c_name,
                   sum(l_extendedprice * (1 - l_discount)) AS revenue,
                   c_acctbal, n_name, c_address, c_phone, c_comment
            FROM {s}.customer, {s}.orders, {s}.lineitem, {s}.nation
            WHERE c_custkey = o_custkey AND l_orderkey = o_orderkey
              AND o_orderdate >= DATE '1993-10-01'
              AND o_orderdate < DATE '1993-10-01' + INTERVAL '3' MONTH
              AND l_returnflag = 'R' AND c_nationkey = n_nationkey
            GROUP BY c_custkey, c_name, c_acctbal, c_phone, n_name,
                     c_address, c_comment
            ORDER BY revenue DESC
            LIMIT 20"""),

        ("Q11", f"""
            SELECT ps_partkey, sum(ps_supplycost * ps_availqty) AS value
            FROM {s}.partsupp, {s}.supplier, {s}.nation
            WHERE ps_suppkey = s_suppkey AND s_nationkey = n_nationkey
              AND n_name = 'GERMANY'
            GROUP BY ps_partkey
            HAVING sum(ps_supplycost * ps_availqty) > (
                SELECT sum(ps_supplycost * ps_availqty) * 0.0001000000
                FROM {s}.partsupp, {s}.supplier, {s}.nation
                WHERE ps_suppkey = s_suppkey AND s_nationkey = n_nationkey
                  AND n_name = 'GERMANY')
            ORDER BY value DESC"""),

        ("Q12", f"""
            SELECT l_shipmode,
                   sum(CASE WHEN o_orderpriority = '1-URGENT'
                              OR o_orderpriority = '2-HIGH'
                            THEN 1 ELSE 0 END) AS high_line_count,
                   sum(CASE WHEN o_orderpriority <> '1-URGENT'
                             AND o_orderpriority <> '2-HIGH'
                            THEN 1 ELSE 0 END) AS low_line_count
            FROM {s}.orders, {s}.lineitem
            WHERE o_orderkey = l_orderkey
              AND l_shipmode IN ('MAIL', 'SHIP')
              AND l_commitdate < l_receiptdate AND l_shipdate < l_commitdate
              AND l_receiptdate >= DATE '1994-01-01'
              AND l_receiptdate < DATE '1994-01-01' + INTERVAL '1' YEAR
            GROUP BY l_shipmode
            ORDER BY l_shipmode"""),

        ("Q13", f"""
            SELECT c_count, count(*) AS custdist
            FROM (
                SELECT c_custkey, count(o_orderkey) AS c_count
                FROM {s}.customer LEFT OUTER JOIN {s}.orders
                  ON c_custkey = o_custkey
                 AND o_comment NOT LIKE '%special%requests%'
                GROUP BY c_custkey
            ) AS c_orders
            GROUP BY c_count
            ORDER BY custdist DESC, c_count DESC"""),

        ("Q14", f"""
            SELECT 100.00 * sum(CASE WHEN p_type LIKE 'PROMO%'
                                     THEN l_extendedprice * (1 - l_discount)
                                     ELSE 0 END)
                   / sum(l_extendedprice * (1 - l_discount)) AS promo_revenue
            FROM {s}.lineitem, {s}.part
            WHERE l_partkey = p_partkey AND l_shipdate >= DATE '1995-09-01'
              AND l_shipdate < DATE '1995-09-01' + INTERVAL '1' MONTH"""),

        ("Q15", f"""
            WITH revenue0 (supplier_no, total_revenue) AS (
                SELECT l_suppkey, sum(l_extendedprice * (1 - l_discount))
                FROM {s}.lineitem
                WHERE l_shipdate >= DATE '1996-01-01'
                  AND l_shipdate < DATE '1996-01-01' + INTERVAL '3' MONTH
                GROUP BY l_suppkey
            )
            SELECT s_suppkey, s_name, s_address, s_phone, total_revenue
            FROM {s}.supplier, revenue0
            WHERE s_suppkey = supplier_no
              AND total_revenue = (SELECT max(total_revenue) FROM revenue0)
            ORDER BY s_suppkey"""),

        ("Q16", f"""
            SELECT p_brand, p_type, p_size,
                   count(DISTINCT ps_suppkey) AS supplier_cnt
            FROM {s}.partsupp, {s}.part
            WHERE p_partkey = ps_partkey AND p_brand <> 'Brand#45'
              AND p_type NOT LIKE 'MEDIUM POLISHED%'
              AND p_size IN (49, 14, 23, 45, 19, 3, 36, 9)
              AND ps_suppkey NOT IN (
                  SELECT s_suppkey FROM {s}.supplier
                  WHERE s_comment LIKE '%Customer%Complaints%')
            GROUP BY p_brand, p_type, p_size
            ORDER BY supplier_cnt DESC, p_brand, p_type, p_size"""),

        ("Q17", f"""
            SELECT sum(l_extendedprice) / 7.0 AS avg_yearly
            FROM {s}.lineitem, {s}.part
            WHERE p_partkey = l_partkey AND p_brand = 'Brand#23'
              AND p_container = 'MED BOX'
              AND l_quantity < (
                  SELECT 0.2 * avg(l_quantity) FROM {s}.lineitem
                  WHERE l_partkey = p_partkey)"""),

        ("Q18", f"""
            SELECT c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice,
                   sum(l_quantity)
            FROM {s}.customer, {s}.orders, {s}.lineitem
            WHERE o_orderkey IN (
                  SELECT l_orderkey FROM {s}.lineitem
                  GROUP BY l_orderkey HAVING sum(l_quantity) > 300)
              AND c_custkey = o_custkey AND o_orderkey = l_orderkey
            GROUP BY c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice
            ORDER BY o_totalprice DESC, o_orderdate
            LIMIT 100"""),

        ("Q19", f"""
            SELECT sum(l_extendedprice * (1 - l_discount)) AS revenue
            FROM {s}.lineitem, {s}.part
            WHERE (p_partkey = l_partkey AND p_brand = 'Brand#12'
                   AND p_container IN ('SM CASE', 'SM BOX', 'SM PACK', 'SM PKG')
                   AND l_quantity >= 1 AND l_quantity <= 1 + 10
                   AND p_size BETWEEN 1 AND 5
                   AND l_shipmode IN ('AIR', 'AIR REG')
                   AND l_shipinstruct = 'DELIVER IN PERSON')
               OR (p_partkey = l_partkey AND p_brand = 'Brand#23'
                   AND p_container IN ('MED BAG', 'MED BOX', 'MED PKG', 'MED PACK')
                   AND l_quantity >= 10 AND l_quantity <= 10 + 10
                   AND p_size BETWEEN 1 AND 10
                   AND l_shipmode IN ('AIR', 'AIR REG')
                   AND l_shipinstruct = 'DELIVER IN PERSON')
               OR (p_partkey = l_partkey AND p_brand = 'Brand#34'
                   AND p_container IN ('LG CASE', 'LG BOX', 'LG PACK', 'LG PKG')
                   AND l_quantity >= 20 AND l_quantity <= 20 + 10
                   AND p_size BETWEEN 1 AND 15
                   AND l_shipmode IN ('AIR', 'AIR REG')
                   AND l_shipinstruct = 'DELIVER IN PERSON')"""),

        ("Q20", f"""
            SELECT s_name, s_address
            FROM {s}.supplier, {s}.nation
            WHERE s_suppkey IN (
                  SELECT ps_suppkey FROM {s}.partsupp
                  WHERE ps_partkey IN (
                        SELECT p_partkey FROM {s}.part
                        WHERE p_name LIKE 'forest%')
                    AND ps_availqty > (
                        SELECT 0.5 * sum(l_quantity) FROM {s}.lineitem
                        WHERE l_partkey = ps_partkey AND l_suppkey = ps_suppkey
                          AND l_shipdate >= DATE '1994-01-01'
                          AND l_shipdate < DATE '1994-01-01' + INTERVAL '1' YEAR))
              AND s_nationkey = n_nationkey AND n_name = 'CANADA'
            ORDER BY s_name"""),

        ("Q21", f"""
            SELECT s_name, count(*) AS numwait
            FROM {s}.supplier, {s}.lineitem l1, {s}.orders, {s}.nation
            WHERE s_suppkey = l1.l_suppkey AND o_orderkey = l1.l_orderkey
              AND o_orderstatus = 'F' AND l1.l_receiptdate > l1.l_commitdate
              AND EXISTS (SELECT * FROM {s}.lineitem l2
                          WHERE l2.l_orderkey = l1.l_orderkey
                            AND l2.l_suppkey <> l1.l_suppkey)
              AND NOT EXISTS (SELECT * FROM {s}.lineitem l3
                              WHERE l3.l_orderkey = l1.l_orderkey
                                AND l3.l_suppkey <> l1.l_suppkey
                                AND l3.l_receiptdate > l3.l_commitdate)
              AND s_nationkey = n_nationkey AND n_name = 'SAUDI ARABIA'
            GROUP BY s_name
            ORDER BY numwait DESC, s_name
            LIMIT 100"""),

        ("Q22", f"""
            SELECT cntrycode, count(*) AS numcust, sum(c_acctbal) AS totacctbal
            FROM (
                SELECT substring(c_phone FROM 1 FOR 2) AS cntrycode, c_acctbal
                FROM {s}.customer
                WHERE substring(c_phone FROM 1 FOR 2)
                        IN ('13', '31', '23', '29', '30', '18', '17')
                  AND c_acctbal > (
                      SELECT avg(c_acctbal) FROM {s}.customer
                      WHERE c_acctbal > 0.00
                        AND substring(c_phone FROM 1 FOR 2)
                              IN ('13', '31', '23', '29', '30', '18', '17'))
                  AND NOT EXISTS (SELECT * FROM {s}.orders
                                  WHERE o_custkey = c_custkey)
            ) AS custsale
            GROUP BY cntrycode
            ORDER BY cntrycode"""),
    ]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="TPC-H 22-query latency benchmark for quack-on-demand",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--url",
                   default=os.environ.get("LT_URL", "grpc+tls://localhost:31338"))
    p.add_argument("-u", "--user", default=os.environ.get("LT_USER", "admin"))
    p.add_argument("-p", "--password", default=os.environ.get("LT_PASSWORD", "admin"))
    p.add_argument("--tenant", default=os.environ.get("LT_TENANT", "acme"),
                   help="tenant gRPC routing header")
    p.add_argument("--pool", default=os.environ.get("LT_POOL", "bi"),
                   help="pool gRPC routing header")
    p.add_argument("--schema", default=os.environ.get("LT_SCHEMA", "tpch1"),
                   help="schema the 22 queries are prefixed with")
    p.add_argument("--runs", type=int, default=int(os.environ.get("LT_RUNS", "5")),
                   help="timed executions per query (median reported)")
    p.add_argument("--warmup", type=int, default=int(os.environ.get("LT_WARMUP", "1")),
                   help="throwaway executions per query before timing")
    p.add_argument("--superuser", action="store_true",
                   default=os.environ.get("LT_SUPERUSER", "true").lower() == "true",
                   help="add the superuser=true gRPC header (bootstrap admin)")
    p.add_argument("--no-superuser", dest="superuser", action="store_false")
    p.add_argument("--insecure", action="store_true",
                   default=os.environ.get("LT_INSECURE", "true").lower() == "true",
                   help="skip TLS certificate verification (dev self-signed cert)")
    p.add_argument("--out-dir",
                   default=os.environ.get("LT_OUT_DIR",
                                          os.path.join(os.path.dirname(__file__), "out")),
                   help="directory for tpch-bench.{csv,html}")
    return p.parse_args()


def connect(args: argparse.Namespace):
    try:
        import adbc_driver_flightsql.dbapi as flight_sql
        from adbc_driver_flightsql import DatabaseOptions
    except ImportError as e:
        print("ERROR: adbc_driver_flightsql not installed.", file=sys.stderr)
        print("       pip install adbc_driver_flightsql adbc_driver_manager pyarrow",
              file=sys.stderr)
        raise SystemExit(2) from e

    db_kwargs = {"username": args.user, "password": args.password}
    if args.insecure and args.url.startswith("grpc+tls"):
        db_kwargs[DatabaseOptions.TLS_SKIP_VERIFY.value] = "true"
    hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
    db_kwargs[hdr + "tenant"] = args.tenant
    db_kwargs[hdr + "pool"] = args.pool
    if args.superuser:
        db_kwargs[hdr + "superuser"] = "true"
    return flight_sql.connect(uri=args.url, db_kwargs=db_kwargs)


class Result:
    def __init__(self, name: str):
        self.name = name
        self.median_ms: Optional[float] = None
        self.min_ms: Optional[float] = None
        self.max_ms: Optional[float] = None
        self.rows: Optional[int] = None
        self.status = "ok"
        self.error = ""


def run_benchmark(args: argparse.Namespace) -> List[Result]:
    queries = tpch_queries(args.schema)
    results: List[Result] = []
    print(f"connecting to {args.url} "
          f"(tenant={args.tenant} pool={args.pool} schema={args.schema}) ...",
          file=sys.stderr)
    conn = connect(args)
    try:
        with conn.cursor() as cur:
            for name, sql in queries:
                r = Result(name)
                try:
                    for _ in range(max(0, args.warmup)):
                        cur.execute(sql)
                        cur.fetchall()
                    samples: List[float] = []
                    rows = 0
                    for _ in range(max(1, args.runs)):
                        t0 = time.perf_counter()
                        cur.execute(sql)
                        fetched = cur.fetchall()
                        samples.append((time.perf_counter() - t0) * 1000.0)
                        rows = len(fetched)
                    r.median_ms = statistics.median(samples)
                    r.min_ms = min(samples)
                    r.max_ms = max(samples)
                    r.rows = rows
                    print(f"  {name:>4}  median={r.median_ms:8.1f} ms  "
                          f"min={r.min_ms:8.1f}  max={r.max_ms:8.1f}  rows={rows}",
                          file=sys.stderr)
                except Exception as e:
                    r.status = "error"
                    r.error = str(e).splitlines()[0] if str(e) else "(no message)"
                    print(f"  {name:>4}  ERROR: {r.error}", file=sys.stderr)
                results.append(r)
    finally:
        conn.close()
    return results


def write_csv(path: str, results: List[Result]) -> None:
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["query", "median_ms", "min_ms", "max_ms", "rows", "status", "error"])
        for r in results:
            w.writerow([
                r.name,
                f"{r.median_ms:.3f}" if r.median_ms is not None else "",
                f"{r.min_ms:.3f}" if r.min_ms is not None else "",
                f"{r.max_ms:.3f}" if r.max_ms is not None else "",
                r.rows if r.rows is not None else "",
                r.status,
                r.error,
            ])


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TPC-H 22-query benchmark - quack-on-demand</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
<style>
  body {{ font-family: -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
         margin: 2rem; color: #1a1a2e; background: #f7f7fb; }}
  h1 {{ font-size: 1.4rem; margin-bottom: .2rem; }}
  .meta {{ color: #555; font-size: .85rem; margin-bottom: 1.5rem; }}
  .meta code {{ background: #eee; padding: 1px 5px; border-radius: 3px; }}
  .card {{ background: #fff; border: 1px solid #e3e3ee; border-radius: 8px;
          padding: 1.2rem; margin-bottom: 1.5rem; box-shadow: 0 1px 3px rgba(0,0,0,.05); }}
  table {{ border-collapse: collapse; width: 100%; font-size: .85rem; }}
  th, td {{ padding: 6px 10px; text-align: right; border-bottom: 1px solid #eee; }}
  th:first-child, td:first-child {{ text-align: left; }}
  th {{ background: #fafafc; }}
  tr.err td {{ color: #b00020; }}
  .num {{ font-variant-numeric: tabular-nums; }}
</style>
</head>
<body>
<h1>TPC-H 22-query benchmark</h1>
<div class="meta">
  endpoint <code>{url}</code> &middot; tenant <code>{tenant}</code> &middot;
  pool <code>{pool}</code> &middot; schema <code>{schema}</code> &middot;
  {runs} timed run(s) / query (warmup {warmup}) &middot; generated {generated}
</div>

<div class="card">
  <canvas id="chart" height="120"></canvas>
</div>

<div class="card">
  <table>
    <thead><tr><th>Query</th><th>Median (ms)</th><th>Min (ms)</th>
      <th>Max (ms)</th><th>Rows</th><th>Status</th></tr></thead>
    <tbody>
      {rows_html}
    </tbody>
  </table>
</div>

<script>
const labels = {labels_json};
const medians = {medians_json};
const ctx = document.getElementById('chart').getContext('2d');
new Chart(ctx, {{
  type: 'bar',
  data: {{
    labels: labels,
    datasets: [{{
      label: 'Median query duration (ms)',
      data: medians,
      backgroundColor: 'rgba(91, 95, 222, 0.75)',
      borderColor: 'rgba(91, 95, 222, 1)',
      borderWidth: 1
    }}]
  }},
  options: {{
    responsive: true,
    plugins: {{
      legend: {{ display: true }},
      title: {{ display: true, text: 'TPC-H query latency (median ms)' }},
      tooltip: {{ callbacks: {{
        label: (c) => c.parsed.y === null ? 'failed' : c.parsed.y.toFixed(1) + ' ms'
      }} }}
    }},
    scales: {{
      x: {{ title: {{ display: true, text: 'Query number' }} }},
      y: {{ title: {{ display: true, text: 'Duration (ms)' }},
            beginAtZero: true }}
    }}
  }}
}});
</script>
</body>
</html>
"""


def write_html(path: str, results: List[Result], args: argparse.Namespace,
               generated: str) -> None:
    rows_html_parts = []
    for r in results:
        cls = ' class="err"' if r.status != "ok" else ""
        def fmt(v):
            return f"{v:.1f}" if v is not None else "-"
        status_cell = html.escape(r.error) if r.status != "ok" else "ok"
        rows_html_parts.append(
            f'<tr{cls}><td>{r.name}</td>'
            f'<td class="num">{fmt(r.median_ms)}</td>'
            f'<td class="num">{fmt(r.min_ms)}</td>'
            f'<td class="num">{fmt(r.max_ms)}</td>'
            f'<td class="num">{r.rows if r.rows is not None else "-"}</td>'
            f'<td>{status_cell}</td></tr>'
        )
    labels = [r.name for r in results]
    medians = [round(r.median_ms, 2) if r.median_ms is not None else None
               for r in results]
    out = HTML_TEMPLATE.format(
        url=html.escape(args.url), tenant=html.escape(args.tenant),
        pool=html.escape(args.pool), schema=html.escape(args.schema),
        runs=args.runs, warmup=args.warmup, generated=generated,
        rows_html="\n      ".join(rows_html_parts),
        labels_json=json.dumps(labels),
        medians_json=json.dumps(medians),
    )
    with open(path, "w") as f:
        f.write(out)


def main() -> int:
    args = parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    results = run_benchmark(args)
    generated = time.strftime("%Y-%m-%d %H:%M:%S %Z")
    csv_path = os.path.join(args.out_dir, "tpch-bench.csv")
    html_path = os.path.join(args.out_dir, "tpch-bench.html")
    write_csv(csv_path, results)
    write_html(html_path, results, args, generated)
    ok = sum(1 for r in results if r.status == "ok")
    print(f"\n{ok}/{len(results)} queries succeeded", file=sys.stderr)
    print(f"CSV : {csv_path}", file=sys.stderr)
    print(f"HTML: {html_path}", file=sys.stderr)
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())