#!/usr/bin/env python3
"""P5a, through the part 2 stack's native Quack front door (127.0.0.1:27494, plain HTTP: the DuckDB
client speaks only plain HTTP to a loopback host), DuckDB 2.0.0 Python client, Q12's passthrough
form: SELECT * FROM quack_query_by_name('q', '<statement>'), the statement text reaching the gateway
unchanged; one execution per statement on the node (no schema probe on this path). The token's
password is read from <p2>/secrets/<user>.txt and never printed.

Usage: env -u VIRTUAL_ENV uv run --no-project --with duckdb==2.0.0 python -I frontdoor_fetch.py <p2> <plain|admin> [runs]
Shapes (interleaved round-robin, fresh rowids unless noted): select1_same, in_same, in_list,
cte_join, in_subq."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json  # noqa: E402
import random  # noqa: E402
import time  # noqa: E402

import duckdb  # noqa: E402

import shapes as S  # noqa: E402


def main():
    p2, user = sys.argv[1], sys.argv[2]
    runs = int(sys.argv[3]) if len(sys.argv) > 3 else 300
    with open(os.path.join(p2, "secrets", f"{user}.txt"), encoding="utf-8") as f:
        password = f.read().strip()
    if not password.isalnum():
        raise SystemExit("password is not alphanumeric: the token would need escaping")
    token = f"tenant=acme&pool=bi&user={user}&password={password}"
    if user == "admin":
        token += "&superuser=true"
    db = duckdb.connect(":memory:")
    db.execute("LOAD quack")
    db.execute(f"ATTACH 'quack:127.0.0.1:27494' AS q (TYPE quack, TOKEN '{token}')")
    del token, password
    rng = random.Random(20261122)

    def via(sql):
        return "SELECT * FROM quack_query_by_name('q', '" + sql.replace("'", "''") + "')"

    makers = {
        "select1_same": (lambda: "SELECT 1", 1),
        "in_same": (lambda: S.in_list(S.SAME), S.K),
        "in_list": (lambda: S.in_list(rng.sample(range(S.ROWS), S.K)), S.K),
        "cte_join": (lambda: S.cte_join(rng.sample(range(S.ROWS), S.K)), S.K),
        "in_subq": (lambda: S.in_subq(rng.sample(range(S.ROWS), S.K)), S.K),
    }
    lat = {k: [] for k in makers}
    for i in range(20 + runs):
        for name, (make, expect) in makers.items():
            sql = via(make())
            t0 = time.perf_counter_ns()
            rows = db.execute(sql).fetchall()
            t1 = time.perf_counter_ns()
            if len(rows) != expect:
                raise SystemExit(f"CHECK FAILED: {name} {len(rows)} rows")
            if i >= 20:
                lat[name].append((t1 - t0) / 1e6)
    for name, samples in lat.items():
        print(json.dumps({"path": "frontdoor", "user": user, "shape": name, **S.summary(samples)}),
              flush=True)


if __name__ == "__main__":
    main()
