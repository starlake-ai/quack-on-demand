#!/usr/bin/env python3
"""P5a: decide calls, verdict-cache lookups and screen calls per statement, from the stack's
/metrics (the replica's qod_authz_decide_duration_ms and qod_authz_decisions_total; the node's
qod_node_authz_*_total, which the leader exports from the health probe's read of qod_authz_stats()
every 5 s; /metrics shares one scrape for 4 s, so every reading waits 11 s first). For each batch:
read, run N statements as the given user on one reused ADBC connection (a cursor per batch), wait,
read; then an idle window as long as the batch plus the wait, read again, so the health probe's own
statements can be subtracted. Usage: ... python -I metrics_delta.py <p2> <plain|admin> [n]"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json  # noqa: E402
import random  # noqa: E402
import re  # noqa: E402
import time  # noqa: E402
import urllib.request  # noqa: E402

import shapes as S  # noqa: E402
from flight_fetch import connect  # noqa: E402

URL = "http://127.0.0.1:27900/metrics"
KEYS = {
    "decide_count": r'^qod_authz_decide_duration_ms_count\{kind="plan"\} ([0-9.e+]+)',
    "decide_sum_ms": r'^qod_authz_decide_duration_ms_sum\{kind="plan"\} ([0-9.e+]+)',
    "screen_decide_count": r'^qod_authz_decide_duration_ms_count\{kind="screen"\} ([0-9.e+]+)',
    "node_decide_calls": r"^qod_node_authz_decide_calls_total\{[^}]*\} ([0-9.e+]+)",
    "node_plan_hits": r"^qod_node_authz_plan_cache_hits_total\{[^}]*\} ([0-9.e+]+)",
    "node_plan_misses": r"^qod_node_authz_plan_cache_misses_total\{[^}]*\} ([0-9.e+]+)",
    "node_screen_calls": r"^qod_node_authz_screen_calls_total\{[^}]*\} ([0-9.e+]+)",
    "node_local_refusals": r"^qod_node_authz_local_refusals_total\{[^}]*\} ([0-9.e+]+)",
}


def read():
    time.sleep(11)
    text = urllib.request.urlopen(URL).read().decode()
    out = {}
    for k, rx in KEYS.items():
        vals = [float(m.group(1)) for m in re.finditer(rx, text, re.M)]
        out[k] = sum(vals)
    return out


def delta(a, b):
    return {k: round(b[k] - a[k], 3) for k in a}


def main():
    p2, user = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 200
    rng = random.Random(20261120)
    batches = {
        "select1_same": lambda: "SELECT 1",
        "in_same": lambda: S.in_list(S.SAME),
        "in_list_fresh": lambda: S.in_list(rng.sample(range(S.ROWS), S.K)),
        "cte_join_fresh": lambda: S.cte_join(rng.sample(range(S.ROWS), S.K)),
    }
    with connect(p2, user, "127.0.0.1", 27338) as conn:
        for name, make in batches.items():
            cur = conn.cursor()
            for _ in range(5):
                cur.execute(make())
                cur.fetchall()
            m0 = read()
            t0 = time.monotonic()
            for _ in range(n):
                cur.execute(make())
                cur.fetchall()
            busy = time.monotonic() - t0
            m1 = read()
            time.sleep(busy)
            m2 = read()
            d, idle = delta(m0, m1), delta(m1, m2)
            per = {k: round((d[k] - idle[k]) / n, 3) for k in d}
            print(json.dumps({"user": user, "batch": name, "n": n, "busy_s": round(busy, 2),
                              "delta": d, "idle_delta": idle, "per_statement_net_of_idle": per}),
                  flush=True)
            cur.close()


if __name__ == "__main__":
    main()
