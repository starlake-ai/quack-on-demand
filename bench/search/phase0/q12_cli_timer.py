#!/usr/bin/env python3
"""Q12: per-statement latency through a DuckDB client (native Quack front door, or the engine alone).

Usage: python3 -I q12_cli_timer.py <duckdb> <prelude.sql> <mode> <table> <runs> [warmup] [client]

  duckdb       the DuckDB 2.0 CLI (client=cli), run as `env -u AI_AGENT <duckdb> -no-agent`.
  prelude.sql  read once at the start of the session: the front door's ATTACH (its TOKEN carries
               the password, so the file stays out of argv and out of this repo), or the
               engine-only ATTACH of a DuckLake.
  mode         direct       run each statement as written, <table> substituted (ATTACH mode
                            through the front door: the client plans and pushes the scan down,
                            and a bare SELECT 1 never leaves the client; or the engine alone);
               passthrough  run SELECT * FROM quack_query_by_name('q', '<statement>'): the
                            statement text goes to the gateway unchanged, one round trip.
  table        the three-part name the statements read (acme_lake.main.pages, q.main.pages, ...).
  client       cli (default): `.timer on`, each statement's `Run Time (s): real` line, which has
                              1 ms resolution;
               python:        the duckdb Python package (2.0.0, run under
                              `uv run --with duckdb==2.0.0`), time.perf_counter around
                              execute + fetchall, sub-millisecond.

Prints p50 and p95 in ms per statement.
"""
import math
import os
import re
import statistics
import subprocess
import sys
import time

duckdb_cli, prelude_path, mode, table, runs = sys.argv[1:6]
runs = int(runs)
warmup = int(sys.argv[6]) if len(sys.argv) > 6 else 5
client = sys.argv[7] if len(sys.argv) > 7 else "cli"

statements = {
    "select-1": "SELECT 1",
    "read-10": "SELECT rowid FROM {t} WHERE id < 10",
    "read-10-id": "SELECT id FROM {t} WHERE id < 10",
    "read-10-filtered-id": "SELECT id FROM {t} WHERE id < 1000 AND title LIKE 't9%' LIMIT 10",
}

with open(prelude_path) as f:
    prelude = f.read()

REAL = re.compile(r"Run Time \(s\): real ([0-9.]+)")


def wrap(sql):
    if mode == "passthrough":
        return "SELECT * FROM quack_query_by_name('q', '" + sql.replace("'", "''") + "')"
    return sql


def report(name, times, rows=None):
    timed = sorted(times)
    extra = f" rows={rows}" if rows is not None else ""
    print(
        f"{name:20s} p50={statistics.median(timed):7.2f} ms "
        # p95: nearest rank, 0-based index ceil(0.95 * n) - 1.
        f"p95={timed[math.ceil(0.95 * len(timed)) - 1]:7.2f} ms (n={len(timed)}){extra}"
    )


def run_cli(name, stmt):
    script = ".bail off\n.mode list\n" + prelude + "\n.timer on\n" + "\n".join(
        [stmt + ";"] * (warmup + runs)
    ) + "\n"
    env = {k: v for k, v in os.environ.items() if k != "AI_AGENT"}
    proc = subprocess.run(
        [duckdb_cli, "-no-agent"], input=script, capture_output=True, text=True, env=env,
        timeout=600,
    )
    out = proc.stdout + proc.stderr
    times = [float(m) * 1000 for m in REAL.findall(out)]
    errors = [line for line in out.splitlines() if "Error" in line]
    if len(times) < warmup + runs or errors:
        print(f"{name:20s} FAILED: {errors[0][:200] if errors else f'only {len(times)} timings'}")
        return
    report(name, times[-runs:])


def run_python(con, name, stmt):
    try:
        for _ in range(warmup):
            rows = con.execute(stmt).fetchall()
    except Exception as exc:  # noqa: BLE001 - a refusal is a finding
        print(f"{name:20s} FAILED: {str(exc).splitlines()[0][:200]}")
        return
    times = []
    for _ in range(runs):
        start = time.perf_counter()
        con.execute(stmt).fetchall()
        times.append((time.perf_counter() - start) * 1000)
    report(name, times, len(rows))


print(f"client={client} mode={mode} table={table} runs={runs} warmup={warmup}")
if client == "python":
    import duckdb

    con = duckdb.connect()
    for stmt in [s for s in prelude.split(";") if s.strip()]:
        con.execute(stmt)
    for name, template in statements.items():
        run_python(con, name, wrap(template.format(t=table)))
else:
    for name, template in statements.items():
        run_cli(name, wrap(template.format(t=table)))
