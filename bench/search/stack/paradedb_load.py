#!/usr/bin/env python3
"""Loads one part 2 corpus into the ParadeDB stack and builds its bm25 index.

Usage: uv run --no-project --with 'psycopg[binary]' --with pyarrow \
         python -I paradedb_load.py <p2> <1m|10m>

Reads <p2>/corpus/pages_<n>.parquet and connects to 127.0.0.1:27432 (sslmode=require) as
postgres, with the password read from <p2>/secrets/paradedb.txt (never printed). Drops and
recreates pages_<n>, streams the Parquet through COPY, runs VACUUM ANALYZE, builds the index,
runs VACUUM ANALYZE again, and prints the load time, the index build time (client wall time),
the row count and the table and index sizes.

Index syntax (public page https://www.paradedb.com/docs/documentation/indexing/create-index,
read 2026-11-14): "`USING bm25` remains supported as a backwards-compatible alias for
`USING paradedb`" and "The `key_field` index option is deprecated and ignored", so the index
names no key_field and keeps id first. f10 and f100 are indexed so the filter can be pushed
into the index (paradedb.enable_filter_pushdown is on by default).
"""
import os
import sys
import time

import psycopg
import pyarrow.parquet as pq

p2, size = sys.argv[1], sys.argv[2]
if size not in ("1m", "10m"):
    sys.exit("size must be 1m or 10m")
table = f"pages_{size}"
parquet = os.path.join(p2, "corpus", f"{table}.parquet")
with open(os.path.join(p2, "secrets", "paradedb.txt")) as f:
    password = f.read().strip()

conn = psycopg.connect(
    host="127.0.0.1", port=27432, dbname="postgres", user="postgres", password=password,
    sslmode="require", autocommit=True,
)
del password
with conn.cursor() as cur:
    cur.execute("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
    if cur.fetchone()[0] is not True:
        sys.exit("connection is not TLS")
    cur.execute(f"DROP TABLE IF EXISTS {table}")
    cur.execute(
        f"CREATE TABLE {table} (id BIGINT PRIMARY KEY, title TEXT, body TEXT, f10 INT, f100 INT)"
    )

    start = time.perf_counter()
    rows = 0
    pf = pq.ParquetFile(parquet)
    with cur.copy(f"COPY {table} (id, title, body, f10, f100) FROM STDIN") as copy:
        for batch in pf.iter_batches(
            batch_size=100000, columns=["id", "title", "body", "f10", "f100"]
        ):
            cols = [batch.column(i).to_pylist() for i in range(5)]
            for row in zip(*cols):
                copy.write_row(row)
            rows += batch.num_rows
    load_s = time.perf_counter() - start
    print(f"copy {table}: {rows} rows in {load_s:.1f} s", flush=True)

    start = time.perf_counter()
    cur.execute(f"VACUUM ANALYZE {table}")
    print(f"vacuum analyze before index: {time.perf_counter() - start:.1f} s", flush=True)

    index_sql = f"CREATE INDEX {table}_bm25 ON {table} USING bm25 (id, body, f10, f100)"
    print(f"index: {index_sql}", flush=True)
    start = time.perf_counter()
    cur.execute(index_sql)
    build_s = time.perf_counter() - start
    print(f"index build {table}_bm25: {build_s:.1f} s", flush=True)

    start = time.perf_counter()
    cur.execute(f"VACUUM ANALYZE {table}")
    print(f"vacuum analyze after index: {time.perf_counter() - start:.1f} s", flush=True)

    cur.execute(
        f"SELECT count(*), pg_relation_size('{table}'), pg_total_relation_size('{table}'), "
        f"pg_relation_size('{table}_bm25') FROM {table}"
    )
    count, heap, total, idx = cur.fetchone()
    print(
        f"rows={count} heap_bytes={heap} total_bytes={total} bm25_index_bytes={idx}",
        flush=True,
    )
conn.close()
