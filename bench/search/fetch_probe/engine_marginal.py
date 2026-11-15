#!/usr/bin/env python3
"""P5a, engine only: what the join-back adds inside one statement that already reads the table, as
the real search query does (its WHERE input scan reads pages_10m before the join-back). Same attach
and settings as engine_fetch.py. Per fraction (f10 = 0, f100 = 0), interleaved round-robin with a
fresh rowid set per execution:
  scan       SELECT max(rowid), count(*) FROM pages_10m WHERE <f> = 0   (the input scan's stand-in)
  fetch      the cte_join fetch alone
  both       WITH hits(rowid) AS (VALUES ...), s AS (<scan>) SELECT p.id, p.title, s.c FROM hits
             JOIN pages_10m p ON p.rowid = hits.rowid CROSS JOIN s       (one statement)
marginal = both - scan (p50 of the paired per-round differences, and the difference of p50s).
Usage: env -u VIRTUAL_ENV uv run --no-project --with duckdb==2.0.0 python -I engine_marginal.py <p2> [runs]"""
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
    runs = int(sys.argv[2]) if len(sys.argv) > 2 else 200
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
    rng = random.Random(20261121)

    def timed(sql, expect):
        t0 = time.perf_counter_ns()
        rows = db.execute(sql).fetchall()
        t1 = time.perf_counter_ns()
        if len(rows) != expect:
            raise SystemExit(f"CHECK FAILED: {len(rows)} rows")
        return (t1 - t0) / 1e6

    for col in ("f10", "f100"):
        scan = f"SELECT max(rowid) AS m, count(*) AS c FROM {S.TABLE} WHERE {col} = 0"
        lat = {"scan": [], "fetch": [], "both": [], "diff": []}
        for i in range(20 + runs):
            ids = rng.sample(range(S.ROWS), S.K)
            values = ", ".join(f"({x})" for x in ids)
            both = (f"WITH hits(rowid) AS (VALUES {values}), s AS ({scan}) "
                    f"SELECT p.id, p.title, s.c FROM hits JOIN {S.TABLE} p ON p.rowid = hits.rowid "
                    f"CROSS JOIN s")
            a = timed(scan, 1)
            b = timed(S.cte_join(rng.sample(range(S.ROWS), S.K)), S.K)
            c = timed(both, S.K)
            if i >= 20:
                lat["scan"].append(a)
                lat["fetch"].append(b)
                lat["both"].append(c)
                lat["diff"].append(c - a)
        out = {"path": "engine-marginal", "filter": f"{col} = 0"}
        for k, v in lat.items():
            sm = S.summary(v)
            out[k + "_p50"], out[k + "_p95"] = sm["p50_ms"], sm["p95_ms"]
        out["n"] = runs
        out["marginal_p50_diff_of_p50s"] = round(out["both_p50"] - out["scan_p50"], 3)
        print(json.dumps(out), flush=True)


if __name__ == "__main__":
    main()
