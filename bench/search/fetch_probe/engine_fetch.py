#!/usr/bin/env python3
"""P5a, engine only: the 10-rowid fetch on the part 2 stack's DuckLake, attached read-only from its
Postgres catalog (DSN read from <p2>/secrets/ducklake.dsn, data path from data_path.txt; neither
printed), DuckDB 2.0.0 in-process with the node's settings that matter (threads 16, memory_limit
62 GiB, enable_external_file_cache and enable_http_metadata_cache false).

Usage: env -u VIRTUAL_ENV uv run --no-project --with duckdb==2.0.0 python -I engine_fetch.py <p2> [runs]

Shapes interleaved round-robin (one execution of each per round, a fresh random rowid set per
execution unless noted), each timed as execute + fetchall with perf_counter_ns:
  select1, in_list (drain.py's fetch), in_same (one fixed text), prepared (? parameters, fresh
  values), cte_join, in_subq, probe (the gateway's LIMIT 0 wrap of in_list); and the same in_list,
  cte_join and probe on a NEW connection per statement (con.cursor()), as a node opens one per
  Flight request. Prints one JSON line per shape."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json  # noqa: E402
import random  # noqa: E402
import time  # noqa: E402

import duckdb  # noqa: E402

import shapes as S  # noqa: E402


def main():
    p2 = sys.argv[1]
    runs = int(sys.argv[2]) if len(sys.argv) > 2 else 300
    with open(os.path.join(p2, "secrets", "ducklake.dsn"), encoding="utf-8") as f:
        dsn = f.read().strip()
    with open(os.path.join(p2, "secrets", "data_path.txt"), encoding="utf-8") as f:
        data_path = f.read().strip()
    db = duckdb.connect(":memory:", config={"threads": 16, "memory_limit": "62GiB"})
    db.execute("SET enable_external_file_cache = false")
    db.execute("SET enable_http_metadata_cache = false")
    db.execute("LOAD ducklake")
    db.execute("LOAD postgres_scanner")
    db.execute("ATTACH ? AS acme_lake (DATA_PATH ?, READ_ONLY)", [dsn, data_path])
    print(json.dumps({"settings": db.execute(
        "SELECT current_setting('threads'), current_setting('memory_limit'), "
        "current_setting('enable_external_file_cache'), current_setting('enable_http_metadata_cache')"
    ).fetchone()}), flush=True)
    rng = random.Random(20261115)

    def fresh():
        return rng.sample(range(S.ROWS), S.K)

    def reused(sql, params=None, expect=S.K):
        def run():
            t0 = time.perf_counter_ns()
            rows = db.execute(sql, params).fetchall() if params else db.execute(sql).fetchall()
            t1 = time.perf_counter_ns()
            if len(rows) != expect:
                raise SystemExit(f"CHECK FAILED: {len(rows)} rows for {sql[:60]}")
            return (t1 - t0) / 1e6
        return run

    def newconn(sql, expect=S.K):
        def run():
            t0 = time.perf_counter_ns()
            c = db.cursor()
            rows = c.execute(sql).fetchall()
            c.close()
            t1 = time.perf_counter_ns()
            if len(rows) != expect:
                raise SystemExit(f"CHECK FAILED: {len(rows)} rows for {sql[:60]}")
            return (t1 - t0) / 1e6
        return run

    cases = {
        "select1": lambda: reused("SELECT 1", expect=1),
        "in_list": lambda: reused(S.in_list(fresh())),
        "in_same": lambda: reused(S.in_list(S.SAME)),
        "prepared": lambda: reused(S.PREPARED_DOLLAR, fresh()),
        "cte_join": lambda: reused(S.cte_join(fresh())),
        "in_subq": lambda: reused(S.in_subq(fresh())),
        "probe": lambda: reused(S.probe(fresh()), expect=0),
        "in_list_newconn": lambda: newconn(S.in_list(fresh())),
        "cte_join_newconn": lambda: newconn(S.cte_join(fresh())),
        "probe_newconn": lambda: newconn(S.probe(fresh()), expect=0),
        "select1_newconn": lambda: newconn("SELECT 1", expect=1),
    }
    lat = {k: [] for k in cases}
    for i in range(20 + runs):
        for name, make in cases.items():
            v = make()()
            if i >= 20:
                lat[name].append(v)
    for name, samples in lat.items():
        print(json.dumps({"path": "engine", "shape": name, **S.summary(samples)}), flush=True)


if __name__ == "__main__":
    main()
