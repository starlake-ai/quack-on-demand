#!/usr/bin/env python3
"""ParadeDB side of the QoD Search Phase 0 part 2 benchmark (spec 11; plan Task 4).

Usage:
  uv run --no-project --with 'psycopg[binary]' --with pyarrow \
    python -I run_paradedb.py <p2> [--client host|vm] [--host H] [--port P]
                              [--password-file F] [--results-dir D] [--dry-run]

Defaults: 127.0.0.1, 27432, <p2>/secrets/paradedb.txt, <p2>/results (<p2> is used for nothing
else). Runs the `where` cells of cells.CELLS (ParadeDB has no row-policy counterpart) on one
reused psycopg connection (autocommit, sslmode=require, password read from F and never printed).
psycopg's automatic server-side prepare (prepare_threshold 5, its default) is left as is.

Per run: the floor `SELECT 1` first, then per cell 20 warm-up executions, pg_stat_statements_reset(),
the timed batches of stopping.run_until_stable (each execution `cursor.execute(sql);
cursor.fetchall()` timed with time.perf_counter_ns), then mean_exec_time of the cell's statement
from pg_stat_statements (server_mean_ms, ParadeDB's own timing method); the floor again last.
Every execution's result must hold 10 rows. One JSON object per cell is appended to
D/paradedb.jsonl once the last floor is measured (floor_p50_ms is the mean of the two floors'
p50); each object is also printed as it completes.

--dry-run: 2 warm-ups, batches of 5, at most 3 batches, objects tagged "dry_run": true and written
to D/paradedb-dryrun.jsonl. A dry run is a check of the runner, never evidence.

--client vm runs this script inside a throwaway container sharing the database container's
network (no Docker Desktop port forward), from /b, which is why the script puts its own directory
on sys.path before importing its siblings.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import argparse  # noqa: E402
import json  # noqa: E402
import statistics  # noqa: E402
import time  # noqa: E402

import psycopg  # noqa: E402

from cells import CELLS, paradedb_sql  # noqa: E402
from stopping import percentile, run_until_stable  # noqa: E402

WARMUP = 20


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("p2")
    ap.add_argument("--client", choices=("host", "vm"), default="host")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27432)
    ap.add_argument("--password-file")
    ap.add_argument("--results-dir")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    a.password_file = a.password_file or os.path.join(a.p2, "secrets", "paradedb.txt")
    a.results_dir = a.results_dir or os.path.join(a.p2, "results")
    return a


def timer(cur, sql):
    def batch(n):
        lat = []
        for _ in range(n):
            t0 = time.perf_counter_ns()
            cur.execute(sql)
            rows = cur.fetchall()
            t1 = time.perf_counter_ns()
            lat.append((t1 - t0) / 1e6)
            if sql != "SELECT 1" and len(rows) != 10:
                raise SystemExit(f"expected 10 rows, got {len(rows)}: {sql}")
        return lat
    return batch


def summary(samples):
    return {
        "p50_ms": round(percentile(samples, 0.50), 4),
        "p95_ms": round(percentile(samples, 0.95), 4),
        "p99_ms": round(percentile(samples, 0.99), 4),
    }


def main():
    a = parse_args()
    batch_size, max_batches, warmup = (5, 3, 2) if a.dry_run else (200, 10, WARMUP)
    with open(a.password_file) as f:
        password = f.read().strip()
    conn = psycopg.connect(host=a.host, port=a.port, dbname="postgres", user="postgres",
                           password=password, sslmode="require", autocommit=True)
    del password
    cur = conn.cursor()
    cur.execute("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
    if cur.fetchone()[0] is not True:
        raise SystemExit("connection is not TLS")
    cur.execute("SELECT extversion FROM pg_extension WHERE extname = 'pg_search'")
    pg_search = cur.fetchone()[0]
    print(f"client={a.client} pg_search={pg_search} prepare_threshold={conn.prepare_threshold} "
          f"dry_run={a.dry_run}", flush=True)

    def floor():
        b = timer(cur, "SELECT 1")
        b(warmup)
        s, n = run_until_stable(b, batch_size, max_batches)
        return percentile(s, 0.50), n

    floor_first, nf1 = floor()
    print(f"floor first p50={floor_first:.4f} ms batches={nf1}", flush=True)

    out = []
    for cell in CELLS:
        if cell.form == "policy":
            continue
        sql = paradedb_sql(cell)
        b = timer(cur, sql)
        b(warmup)
        cur.execute(sql)
        ids = [r[0] for r in cur.fetchall()]
        cur.execute("SELECT pg_stat_statements_reset()")
        samples, runs = run_until_stable(b, batch_size, max_batches)
        cur.execute(
            "SELECT calls, mean_exec_time, mean_plan_time FROM pg_stat_statements "
            "WHERE query LIKE %s AND query NOT LIKE %s",
            (f"%FROM pages_{cell.size} WHERE body |||%", "%pg_stat_statements%"),
        )
        stat = cur.fetchall()
        if len(stat) != 1:
            raise SystemExit(f"expected one pg_stat_statements row for {sql}, got {len(stat)}")
        calls, mean_exec, mean_plan = stat[0]
        obj = {"side": "paradedb", "size": cell.size, "input": cell.input, "form": cell.form,
               "query": cell.query, **summary(samples), "runs": runs, "samples": len(samples),
               "server_mean_ms": round(mean_exec, 4),
               "server_plan_mean_ms": round(mean_plan or 0.0, 4), "server_calls": calls,
               "client": a.client, "top_ids": ids}
        if a.dry_run:
            obj["dry_run"] = True
        print(json.dumps(obj), flush=True)
        out.append(obj)

    floor_last, nf2 = floor()
    print(f"floor last p50={floor_last:.4f} ms batches={nf2}", flush=True)
    fl = (floor_first + floor_last) / 2
    for obj in out:
        obj["floor_p50_ms"] = round(fl, 4)
        obj["floor_first_p50_ms"] = round(floor_first, 4)
        obj["floor_last_p50_ms"] = round(floor_last, 4)
    os.makedirs(a.results_dir, exist_ok=True)
    name = "paradedb-dryrun.jsonl" if a.dry_run else "paradedb.jsonl"
    with open(os.path.join(a.results_dir, name), "a") as f:
        for obj in out:
            f.write(json.dumps(obj) + "\n")
    print(f"wrote {len(out)} cells to {os.path.join(a.results_dir, name)}", flush=True)
    conn.close()


if __name__ == "__main__":
    main()
