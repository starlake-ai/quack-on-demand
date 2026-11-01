"""FlightSQL plane over ADBC, adapted from examples/python/client.py.

pyarrow and the ADBC driver import lazily inside functions so `qod tenant
list` never pays their import cost.
"""

from __future__ import annotations

import csv
import io
import json
import sys

from .config import Settings


class SqlClient:
    def __init__(self, conn):
        self._conn = conn

    @classmethod
    def connect(cls, settings: Settings) -> "SqlClient":
        try:
            import adbc_driver_flightsql.dbapi as flight_sql
            from adbc_driver_flightsql import DatabaseOptions
        except ImportError:
            print(
                "error: the FlightSQL client (adbc-driver-flightsql) is not installed.\n"
                "It ships no wheels for this platform (e.g. Windows on ARM64), so qod\n"
                "skips it there. Run `qod sql` from an x86-64 Python, or point another\n"
                "FlightSQL client at the edge. The `qod start` manager is unaffected.",
                file=sys.stderr,
            )
            raise SystemExit(1)

        scheme = "grpc+tls" if settings.edge_tls else "grpc"
        uri = f"{scheme}://{settings.edge_host}:{settings.edge_port}"
        hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
        db_kwargs = {
            "username": settings.sql_user,
            "password": settings.sql_password,
            hdr + "tenant": settings.tenant,
            hdr + "pool": settings.pool,
        }
        if settings.superuser:
            db_kwargs[hdr + "superuser"] = "true"
        if getattr(settings, "branch", ""):
            # Branch targeting (Epic 1): authorization runs against the parent pool above; the
            # session is bound to the branch's own pool.
            db_kwargs[hdr + "branch"] = settings.branch
        if settings.edge_tls and not settings.edge_tls_verify:
            db_kwargs[DatabaseOptions.TLS_SKIP_VERIFY.value] = "true"
        conn = flight_sql.connect(uri=uri, db_kwargs=db_kwargs, autocommit=True)
        return cls(conn)

    def query(self, sql: str):
        with self._conn.cursor() as cur:
            cur.execute(sql)
            return cur.fetch_arrow_table()

    def close(self) -> None:
        self._conn.close()

    def __enter__(self) -> "SqlClient":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()


def _unique_names(names: list[str]) -> list[str]:
    """One key per column, in order: a repeated name gets DuckDB's `_1`, `_2`, ... suffix,
    skipping any name another column already holds (`SELECT 17 AS id, 23 AS id` is valid SQL,
    but a row dict holds one value per key)."""
    taken = set(names)
    seen: set[str] = set()
    out: list[str] = []
    for name in names:
        key = name
        if key in seen:
            n = 1
            while f"{name}_{n}" in taken or f"{name}_{n}" in seen:
                n += 1
            key = f"{name}_{n}"
        seen.add(key)
        out.append(key)
    return out


def _cell_value(table, column: int, index: int):
    # By position: pyarrow refuses a name the schema holds twice.
    value = table.column(column)[index].as_py()
    return value if isinstance(value, (int, float, str, bool, type(None))) else str(value)


def _row(table, index: int, keys: list[str]) -> dict:
    return {key: _cell_value(table, i, index) for i, key in enumerate(keys)}


def render_table(table, mode: str) -> None:
    if mode == "csv":
        # CSV keeps the result's own header, repeated names included.
        buf = io.StringIO()
        writer = csv.writer(buf)
        writer.writerow(table.column_names)
        for i in range(table.num_rows):
            writer.writerow([_cell_value(table, c, i) for c in range(table.num_columns)])
        sys.stdout.write(buf.getvalue())
        return
    keys = _unique_names(table.column_names)
    rows = [_row(table, i, keys) for i in range(table.num_rows)]
    if mode == "json":
        print(json.dumps(rows, indent=2, default=str))
        return
    from .output import render

    render(rows if rows else f"(empty result: {', '.join(table.column_names)})", as_json=False)
    print(f"-- {table.num_rows} rows", file=sys.stderr)
