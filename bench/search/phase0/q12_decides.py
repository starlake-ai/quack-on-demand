#!/usr/bin/env python3
"""Q12: how many node decisions a statement costs, per path, read from the manager's /metrics.

Usage: python3 -I q12_decides.py <metrics url> <mode> <user> [prelude.sql]

  mode  flight-first  Flight SQL (ADBC, TLS, 127.0.0.1:24338 unless Q12_FLIGHT_URI is set): for each
                      statement shape not run before, the deltas after cursor.execute (GetFlightInfo)
                      and after fetchall (DoGet), then after a second run of the same shape.
        quack-first   native front door (duckdb 2.0.0 Python client, ATTACH from prelude.sql, the
                      statement through quack_query_by_name): the deltas after a first and a second run.
        flight-partitions  Flight SQL, GetFlightInfo alone (adbc_execute_partitions), then the
                      DoGet of each endpoint (adbc_read_partition), first and second run per shape.
        repeat        Flight SQL then front door: 20 runs of two shapes, deltas per statement
                      (meant for a stack started with QOD_AUTHZ_CACHE_ENTRIES=0).

The user's password comes from the environment, Q12_<USER>_PASSWORD (never argv). Shapes read
distinct column sets with no WHERE, so no two share a verdict fingerprint (constants and aliases do
share one); sets a, b and c are disjoint: flight-first uses a, quack-first b, flight-partitions c.

Counters: the replica's qod_authz_decide_duration_ms{kind="plan"} count and sum (recorded by the
decide route itself), and the node's qod_node_authz_{decide_calls,plan_cache_misses,plan_cache_hits,
screen_calls}_total, which the leader exports from the health probe's read of qod_authz_stats()
(every 5 s). The metrics endpoint shares one scrape for 4 s, so each reading waits 11 s first.
screen_calls includes the probe's own reads, so its delta carries a few calls of noise.
"""
import os
import re
import sys
import time
import urllib.request

metrics_url, mode, user = sys.argv[1], sys.argv[2], sys.argv[3]
prelude_path = sys.argv[4] if len(sys.argv) > 4 else None
password = os.environ[f"Q12_{user.upper()}_PASSWORD"]
table = os.environ.get("Q12_TABLE", "acme_lake.main.pages")
WAIT = 11

SHAPES = {
    "a": ["id", "title", "body", "cat", "id, title"],
    "b": ["title, body", "title, cat", "body, cat", "id, cat", "id, title, body"],
    "c": ["id, body", "id, title, cat", "id, body, cat", "title, body, cat", "id, title, body, cat"],
}

NODE = ["decide_calls", "plan_cache_misses", "plan_cache_hits", "screen_calls"]


def reading():
    time.sleep(WAIT)
    text = urllib.request.urlopen(metrics_url).read().decode()

    def one(pattern):
        m = re.search(pattern + r" ([0-9.eE+]+)", text)
        return float(m.group(1)) if m else 0.0

    r = {
        "replica_decides": one(r'qod_authz_decide_duration_ms_count\{kind="plan"\}'),
        "replica_ms": one(r'qod_authz_decide_duration_ms_sum\{kind="plan"\}'),
    }
    for c in NODE:
        r[c] = one(r"qod_node_authz_" + c + r"_total\{[^}]*\}")
    return r


def delta(a, b, per=1):
    d = {k: (b[k] - a[k]) / per for k in a}
    judged = d["replica_ms"] / d["replica_decides"] if d["replica_decides"] else 0.0
    return (
        f"replica_decides={d['replica_decides']:g} (judging {judged:.3f} ms each) "
        + " ".join(f"node_{c}={d[c]:g}" for c in NODE)
    )


def flight_cursor():
    import adbc_driver_flightsql.dbapi as flight
    from adbc_driver_flightsql import DatabaseOptions as O

    h = O.RPC_CALL_HEADER_PREFIX.value
    uri = os.environ.get("Q12_FLIGHT_URI", "grpc+tls://127.0.0.1:24338")
    conn = flight.connect(
        uri,
        db_kwargs={
            "username": user, "password": password, O.TLS_SKIP_VERIFY.value: "true",
            h + "tenant": "acme", h + "pool": "bi",
        },
        autocommit=True,
    )
    return conn, conn.cursor()


def quack_connection():
    import duckdb

    con = duckdb.connect()
    with open(prelude_path) as f:
        for stmt in [s for s in f.read().split(";") if s.strip()]:
            con.execute(stmt)
    return con


def via_front_door(sql):
    return "SELECT * FROM quack_query_by_name('q', '" + sql.replace("'", "''") + "')"


if mode == "flight-first":
    conn, cur = flight_cursor()
    shapes = ["1"] + SHAPES[os.environ.get("Q12_SHAPESET", "a")]
    base = reading()
    print("idle", WAIT, "s:", delta(base, reading()))
    for cols in shapes:
        sql = "SELECT 1" if cols == "1" else f"SELECT {cols} FROM {table} LIMIT 10"
        s0 = reading()
        cur.execute(sql)
        s1 = reading()
        cur.fetchall()
        s2 = reading()
        cur.execute(sql)
        cur.fetchall()
        s3 = reading()
        print(f"[{sql}] first run, GetFlightInfo: {delta(s0, s1)}")
        print(f"[{sql}] first run, DoGet:         {delta(s1, s2)}")
        print(f"[{sql}] second run (whole):     {delta(s2, s3)}")
    cur.close()
    conn.close()
elif mode == "flight-partitions":
    # adbc_execute_partitions is GetFlightInfo alone (ADBC's execute starts the DoGet stream in
    # the background, so the execute / fetchall split above does not separate the two RPCs);
    # adbc_read_partition then runs the DoGet of each endpoint.
    conn, cur = flight_cursor()
    shapes = SHAPES[os.environ.get("Q12_SHAPESET", "c")]
    base = reading()
    print("idle", WAIT, "s:", delta(base, reading()))
    for cols in shapes:
        sql = f"SELECT {cols} FROM {table} LIMIT 10"
        s0 = reading()
        partitions, _schema = cur.adbc_execute_partitions(sql)
        s1 = reading()
        for p in partitions:
            cur.adbc_read_partition(p)
            cur.fetchall()
        s2 = reading()
        partitions, _schema = cur.adbc_execute_partitions(sql)
        s3 = reading()
        for p in partitions:
            cur.adbc_read_partition(p)
            cur.fetchall()
        s4 = reading()
        print(f"[{sql}] first run, GetFlightInfo only: {delta(s0, s1)}")
        print(f"[{sql}] first run, DoGet:              {delta(s1, s2)}")
        print(f"[{sql}] second run, GetFlightInfo only: {delta(s2, s3)}")
        print(f"[{sql}] second run, DoGet:              {delta(s3, s4)}")
    cur.close()
    conn.close()
elif mode == "quack-first":
    con = quack_connection()
    shapes = ["'a' || 'b'"] + SHAPES[os.environ.get("Q12_SHAPESET", "b")]
    base = reading()
    print("idle", WAIT, "s:", delta(base, reading()))
    for cols in shapes:
        sql = f"SELECT {cols}" if cols.startswith("'") else f"SELECT {cols} FROM {table} LIMIT 10"
        s0 = reading()
        con.execute(via_front_door(sql)).fetchall()
        s1 = reading()
        con.execute(via_front_door(sql)).fetchall()
        s2 = reading()
        print(f"[{sql}] first run:  {delta(s0, s1)}")
        print(f"[{sql}] second run: {delta(s1, s2)}")
elif mode == "repeat":
    n = 20
    conn, cur = flight_cursor()
    con = quack_connection()
    for path in ("flight", "front door"):
        for sql in ("SELECT 1", f"SELECT id FROM {table} WHERE id < 10"):
            if path == "flight":
                run = lambda: (cur.execute(sql), cur.fetchall())  # noqa: E731
            else:
                run = lambda: con.execute(via_front_door(sql)).fetchall()  # noqa: E731
            run()
            a = reading()
            for _ in range(n):
                run()
            b = reading()
            print(f"{path:10s} [{sql}] per statement over {n}: {delta(a, b, n)}")
    cur.close()
    conn.close()
else:
    sys.exit(f"unknown mode {mode}")
