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


def _row(table, index: int) -> dict:
    out: dict = {}
    for name in table.column_names:
        value = table.column(name)[index].as_py()
        out[name] = value if isinstance(value, (int, float, str, bool, type(None))) else str(value)
    return out


def render_table(table, mode: str) -> None:
    rows = [_row(table, i) for i in range(table.num_rows)]
    if mode == "json":
        print(json.dumps(rows, indent=2, default=str))
        return
    if mode == "csv":
        buf = io.StringIO()
        writer = csv.DictWriter(buf, fieldnames=table.column_names)
        writer.writeheader()
        writer.writerows(rows)
        sys.stdout.write(buf.getvalue())
        return
    from .output import render

    render(rows if rows else f"(empty result: {', '.join(table.column_names)})", as_json=False)
    print(f"-- {table.num_rows} rows", file=sys.stderr)
