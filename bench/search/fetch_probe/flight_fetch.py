#!/usr/bin/env python3
"""P5a, through the part 2 stack's Flight SQL edge (ADBC, TLS, certificate not verified as the qod
CLI does; tenant acme and pool bi as call headers, Q12's pattern), on one reused connection.

Usage: env -u VIRTUAL_ENV uv run --no-project --with adbc-driver-flightsql --with pyarrow \
         python -I flight_fetch.py <p2> <plain|admin> [runs] [--shapes a,b,...]

`admin` connects as the stack's superuser (call header superuser=true), whose statements the node
admits without a decide call. Passwords are read from <p2>/secrets/<user>.txt, never printed.

Shapes, interleaved round-robin (one execution of each per round; a fresh random rowid set per
execution unless noted), each timed with perf_counter_ns:
  select1     SELECT 1 (the floor), execute + fetchall
  in_list     drain.py's fetch, fresh rowids
  in_same     the same text every time
  prepared    one ? parameter per rowid, fresh values; ADBC prepares once (same text) and binds
  cte_join    WITH hits(rowid) AS (VALUES ...) SELECT ... JOIN ... (spec 7.7 one-statement shape)
  in_subq     WHERE rowid IN (SELECT rowid FROM (VALUES ...))
  split_*     in_list / in_same / select1 through adbc_execute_partitions (GetFlightInfo, which
              carries the gateway's schema probe) and adbc_read_partition + read_all (DoGet),
              each half timed on its own
Prints one JSON line per shape (split shapes give gfi, doget and total)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import argparse  # noqa: E402
import json  # noqa: E402
import random  # noqa: E402
import time  # noqa: E402

import adbc_driver_flightsql.dbapi as flight  # noqa: E402
from adbc_driver_flightsql import DatabaseOptions  # noqa: E402

import shapes as S  # noqa: E402

HDR = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
ALL = ["select1", "in_list", "in_same", "prepared", "cte_join", "in_subq",
       "split_select1", "split_in_list", "split_in_same"]


def connect(p2, user, host, port):
    with open(os.path.join(p2, "secrets", f"{user}.txt"), encoding="utf-8") as f:
        password = f.read().strip()
    kw = {"username": user, "password": password, DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
          HDR + "tenant": "acme", HDR + "pool": "bi"}
    if user == "admin":
        kw[HDR + "superuser"] = "true"
    return flight.connect(f"grpc+tls://{host}:{port}", db_kwargs=kw, autocommit=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("p2")
    ap.add_argument("user", choices=["plain", "admin"])
    ap.add_argument("runs", type=int, nargs="?", default=300)
    ap.add_argument("--shapes", default=",".join(ALL))
    ap.add_argument("--warmup", type=int, default=20)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=27338)
    a = ap.parse_args()
    names = [s for s in a.shapes.split(",") if s]
    rng = random.Random(20261116)

    def fresh():
        return rng.sample(range(S.ROWS), S.K)

    with connect(a.p2, a.user, a.host, a.port) as conn:
        cur = conn.cursor()       # plain execute shapes
        pcur = conn.cursor()      # the prepared shape keeps its own cursor (one prepare)

        def plain(make_sql, expect=S.K):
            def run():
                sql = make_sql()
                t0 = time.perf_counter_ns()
                cur.execute(sql)
                rows = cur.fetchall()
                t1 = time.perf_counter_ns()
                if len(rows) != expect:
                    raise SystemExit(f"CHECK FAILED: {len(rows)} rows")
                return {"total": (t1 - t0) / 1e6}
            return run

        def prepared():
            params = tuple(fresh())
            t0 = time.perf_counter_ns()
            pcur.execute(S.PREPARED, params)
            rows = pcur.fetchall()
            t1 = time.perf_counter_ns()
            if len(rows) != S.K:
                raise SystemExit(f"CHECK FAILED: prepared {len(rows)} rows")
            return {"total": (t1 - t0) / 1e6}

        def split(make_sql, expect=S.K):
            def run():
                sql = make_sql()
                t0 = time.perf_counter_ns()
                parts, _schema = cur.adbc_execute_partitions(sql)
                t1 = time.perf_counter_ns()
                n = 0
                for p in parts:
                    cur.adbc_read_partition(p)
                    n += cur.fetch_arrow_table().num_rows
                t2 = time.perf_counter_ns()
                if n != expect:
                    raise SystemExit(f"CHECK FAILED: split {n} rows")
                return {"gfi": (t1 - t0) / 1e6, "doget": (t2 - t1) / 1e6, "total": (t2 - t0) / 1e6}
            return run

        cases = {
            "select1": plain(lambda: "SELECT 1", 1),
            "in_list": plain(lambda: S.in_list(fresh())),
            "in_same": plain(lambda: S.in_list(S.SAME)),
            "prepared": prepared,
            "cte_join": plain(lambda: S.cte_join(fresh())),
            "in_subq": plain(lambda: S.in_subq(fresh())),
            "split_select1": split(lambda: "SELECT 1", 1),
            "split_in_list": split(lambda: S.in_list(fresh())),
            "split_in_same": split(lambda: S.in_list(S.SAME)),
        }
        cases = {k: cases[k] for k in names}
        lat = {k: {} for k in cases}
        errors = {}
        for i in range(a.warmup + a.runs):
            for name, run in cases.items():
                if name in errors:
                    continue
                try:
                    r = run()
                except SystemExit:
                    raise
                except Exception as exc:  # a refused shape is a finding, recorded once
                    errors[name] = f"{type(exc).__name__}: {str(exc)[:300]}"
                    continue
                if i >= a.warmup:
                    for k, v in r.items():
                        lat[name].setdefault(k, []).append(v)
        for name in cases:
            if name in errors:
                print(json.dumps({"path": "flight", "user": a.user, "shape": name,
                                  "error": errors[name]}), flush=True)
                continue
            out = {"path": "flight", "user": a.user, "shape": name}
            for k, samples in lat[name].items():
                sm = S.summary(samples)
                out[k + "_p50_ms"], out[k + "_p95_ms"], out["n"] = sm["p50_ms"], sm["p95_ms"], sm["n"]
            print(json.dumps(out), flush=True)
        cur.close()
        pcur.close()


if __name__ == "__main__":
    main()
