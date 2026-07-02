"""Run the 22 TPC-H benchmark queries against the Quack-on-Demand FlightSQL edge
and report the row count, latency, and first-row preview for each.

Run:  python tpch.py              # all 22 queries
      python tpch.py 1 6 14       # just queries 1, 6 and 14

The target schema defaults to ``tpch1`` (override with QOD_TPCH_SCHEMA); all
other connection settings come from the same QOD_* env vars query.py uses (see
README). The defaults connect as the superuser admin to acme/bi.
"""

import json
import os
import sys
import time

import pyarrow as pa

from client import QodClient, config_from_env, row_to_dict
from tpch_queries import TPCH_QUERIES, qualify

SCHEMA = os.environ.get("QOD_TPCH_SCHEMA", "tpch1")


def _selected_ids() -> set[int]:
    ids = set()
    for arg in sys.argv[1:]:
        try:
            ids.add(int(arg))
        except ValueError:
            pass
    return ids


def preview_row(table: pa.Table) -> str:
    if table.num_rows == 0:
        return "(no rows)"
    return json.dumps(row_to_dict(table, 0))


def main() -> None:
    selected = _selected_ids()
    queries = [q for q in TPCH_QUERIES if q.id in selected] if selected else TPCH_QUERIES

    with QodClient.connect(config_from_env()) as client:
        print(f"Connecting to {client.describe()}")
        print(f"Running {len(queries)} TPC-H query(ies) against schema '{SCHEMA}'\n")

        ok = 0
        failed = 0
        total_ms = 0.0

        for q in queries:
            label = f"Q{q.id:02d} {q.title}"
            sql = qualify(q.sql, SCHEMA)
            started = time.perf_counter()
            try:
                table = client.query(sql)
                ms = (time.perf_counter() - started) * 1000
                total_ms += ms
                ok += 1
                print(
                    f"{label:<42} {ms:6.0f} ms  "
                    f"{table.num_rows:6d} rows  {preview_row(table)}"
                )
            except Exception as err:  # noqa: BLE001 - report and continue
                failed += 1
                print(f"{label:<42} FAILED  {err}")

        avg = total_ms / max(ok, 1)
        print(f"\n{ok} ok, {failed} failed, {total_ms:.0f} ms total ({avg:.0f} ms avg)")

    if failed > 0:
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as err:  # noqa: BLE001 - top-level CLI error reporting
        print(f"\ntpch run failed: {err}", file=sys.stderr)
        sys.exit(1)