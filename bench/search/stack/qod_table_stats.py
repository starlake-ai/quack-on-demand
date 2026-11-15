#!/usr/bin/env python3
"""Prints, per part 2 table on the QoD stack: the row count, every live data file with its
row_id_start and record count, and each file's Parquet row group count and sizes.

Usage: uv run --no-project --with adbc-driver-flightsql --with pyarrow python -I qod_table_stats.py <p2> [table ...]

Superuser (admin, superuser=true) through Flight SQL on 127.0.0.1:27338, tenant acme, pool bi;
the password is read from <p2>/secrets/admin.txt, never printed. File paths are printed
relative to the catalog's data path. Two steps per table because parquet_metadata takes no
subquery argument: the catalog gives the paths, then each path is passed as a literal.
"""
import os
import sys

import adbc_driver_flightsql.dbapi as flight
from adbc_driver_flightsql import DatabaseOptions

P2 = sys.argv[1]
tables = sys.argv[2:] or ["pages_1m", "pages_10m", "pages_100m_drain", "other"]
with open(os.path.join(P2, "secrets", "admin.txt")) as f:
    password = f.read().strip()
hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
conn = flight.connect(
    "grpc+tls://127.0.0.1:27338",
    db_kwargs={
        "username": "admin", "password": password,
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
        hdr + "tenant": "acme", hdr + "pool": "bi", hdr + "superuser": "true",
    },
    autocommit=True,
)
del password
M = "__ducklake_metadata_acme_lake"


def q(cur, sql):
    cur.execute(sql)
    return cur.fetchall()


def lit(s):
    return "'" + s.replace("'", "''") + "'"


with conn.cursor() as cur:
    data_path = q(cur, f"SELECT value FROM {M}.ducklake_metadata WHERE key = 'data_path'")[0][0]
    for t in tables:
        count = q(cur, f"SELECT count(*) FROM acme_lake.main.{t}")[0][0]
        files = q(cur, f"""
            SELECT CASE WHEN f.path_is_relative
                        THEN coalesce(s.path, '') || coalesce(tb.path, '') || f.path ELSE f.path END,
                   f.path_is_relative, f.row_id_start, f.record_count, f.file_size_bytes
            FROM {M}.ducklake_data_file f
            JOIN {M}.ducklake_table tb USING (table_id)
            JOIN {M}.ducklake_schema s ON s.schema_id = tb.schema_id
            WHERE tb.table_name = {lit(t)} AND f.end_snapshot IS NULL
              AND tb.end_snapshot IS NULL AND s.end_snapshot IS NULL
            ORDER BY f.row_id_start""")
        print(f"{t}: rows={count} files={len(files)} "
              f"null_row_id_start={sum(1 for x in files if x[2] is None)}")
        for path, relative, rid, n, size in files:
            full = data_path + path if relative else path
            rg = q(cur, f"""
                SELECT count(DISTINCT row_group_id), min(row_group_num_rows), max(row_group_num_rows)
                FROM parquet_metadata({lit(full)})""")[0]
            shown = path if relative else os.path.relpath(full, data_path)
            print(f"  {shown} row_id_start={rid} records={n} bytes={size} "
                  f"row_groups={rg[0]} rg_rows_min={rg[1]} rg_rows_max={rg[2]}")
conn.close()
