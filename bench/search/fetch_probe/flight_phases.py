#!/usr/bin/env python3
"""P5a, Flight SQL phases: where the time of one statement goes on the ADBC path.

Usage: env -u VIRTUAL_ENV uv run --no-project --with adbc-driver-flightsql --with pyarrow \
         python -I flight_phases.py <p2> <plain|admin> [runs] [--shapes a,b] [--warmup n]

The ADBC DB-API cursor prepares a statement (Flight SQL CreatePreparedStatement, which QoD's edge
answers by running the schema probe `SELECT * FROM (<sql>) AS _qod_probe LIMIT 0` on a node) only
when the text differs from the cursor's previous one; then GetFlightInfo on the prepared handle
(answered from the cached schema, no node) and DoGet (the node runs the statement). Each shape has
its own cursor, so a "same" shape really repeats its text, and each execution is timed in three
phases: prepare (adbc_prepare; 0 when the text is unchanged), gfi (adbc_execute_partitions), doget
(adbc_read_partition + fetch_arrow_table). `direct_in_list` skips the prepare: a bare ADBC
statement per execution (set_sql_query, execute_partitions), so its gfi is GetFlightInfo on a
CommandStatementQuery, which the edge answers by running the same probe.

Shapes: select1_same, select1_fresh (SELECT <random int>: a new text, the same fingerprint),
in_same, in_list (fresh rowids), cte_join, in_subq, direct_in_list. Round-robin interleaved."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import argparse  # noqa: E402
import json  # noqa: E402
import random  # noqa: E402
import time  # noqa: E402

import adbc_driver_manager  # noqa: E402

import shapes as S  # noqa: E402
from flight_fetch import connect  # noqa: E402

ALL = ["select1_same", "select1_fresh", "in_same", "in_list", "cte_join", "in_subq", "direct_in_list"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("p2")
    ap.add_argument("user", choices=["plain", "admin"])
    ap.add_argument("runs", type=int, nargs="?", default=300)
    ap.add_argument("--shapes", default=",".join(ALL))
    ap.add_argument("--warmup", type=int, default=20)
    a = ap.parse_args()
    names = [s for s in a.shapes.split(",") if s]
    rng = random.Random(20261117)

    def fresh():
        return rng.sample(range(S.ROWS), S.K)

    makers = {
        "select1_same": (lambda: "SELECT 1", 1),
        "select1_fresh": (lambda: f"SELECT {rng.randrange(1, 10**9)}", 1),
        "in_same": (lambda: S.in_list(S.SAME), S.K),
        "in_list": (lambda: S.in_list(fresh()), S.K),
        "cte_join": (lambda: S.cte_join(fresh()), S.K),
        "in_subq": (lambda: S.in_subq(fresh()), S.K),
        "direct_in_list": (lambda: S.in_list(fresh()), S.K),
    }
    with connect(a.p2, a.user, "127.0.0.1", 27338) as conn:
        curs = {n: conn.cursor() for n in names}
        lat = {n: {"prepare": [], "gfi": [], "doget": [], "total": []} for n in names}

        def run(name):
            make, expect = makers[name]
            sql = make()
            cur = curs[name]
            if name == "direct_in_list":
                stmt = adbc_driver_manager.AdbcStatement(conn.adbc_connection)
                t0 = time.perf_counter_ns()
                stmt.set_sql_query(sql)
                t1 = time.perf_counter_ns()
                parts, _schema, _rc = stmt.execute_partitions()
                t2 = time.perf_counter_ns()
                n = 0
                for p in parts:
                    cur.adbc_read_partition(p)
                    n += cur.fetch_arrow_table().num_rows
                t3 = time.perf_counter_ns()
                stmt.close()
            else:
                t0 = time.perf_counter_ns()
                cur.adbc_prepare(sql)
                t1 = time.perf_counter_ns()
                parts, _schema = cur.adbc_execute_partitions(sql)
                t2 = time.perf_counter_ns()
                n = 0
                for p in parts:
                    cur.adbc_read_partition(p)
                    n += cur.fetch_arrow_table().num_rows
                t3 = time.perf_counter_ns()
            if n != expect:
                raise SystemExit(f"CHECK FAILED: {name} {n} rows")
            return {"prepare": (t1 - t0) / 1e6, "gfi": (t2 - t1) / 1e6,
                    "doget": (t3 - t2) / 1e6, "total": (t3 - t0) / 1e6}

        for i in range(a.warmup + a.runs):
            for name in names:
                r = run(name)
                if i >= a.warmup:
                    for k, v in r.items():
                        lat[name][k].append(v)
        for name in names:
            out = {"path": "flight-phases", "user": a.user, "shape": name}
            for k, samples in lat[name].items():
                sm = S.summary(samples)
                out[k + "_p50"], out[k + "_p95"], out["n"] = sm["p50_ms"], sm["p95_ms"], sm["n"]
            print(json.dumps(out), flush=True)
        for c in curs.values():
            c.close()


if __name__ == "__main__":
    main()
