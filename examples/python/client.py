"""Reusable FlightSQL client for Quack-on-Demand, over ADBC.

Unlike Node (which has no first-party Flight SQL driver and must hand-roll the
gRPC + Arrow plumbing), Python has the Arrow ADBC Flight SQL driver, so this is
a thin wrapper: build the connection options from ``QOD_*`` env vars, run a
statement, and hand back a ``pyarrow.Table``.

The edge authenticates every RPC from the call headers (``tenant``, ``pool``,
``authorization: Basic <base64>``), so there is no separate handshake step.
ADBC carries the Basic credential via the ``username`` / ``password`` options
and the routing values as per-RPC call headers under ``RPC_CALL_HEADER_PREFIX``.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import adbc_driver_flightsql.dbapi as flight_sql
import pyarrow as pa
from adbc_driver_flightsql import DatabaseOptions


def _env_bool(name: str, default: bool) -> bool:
    return os.environ.get(name, str(default).lower()) == "true"


@dataclass(frozen=True)
class QodConfig:
    host: str
    port: int
    user: str
    password: str
    tenant: str
    pool: str
    superuser: bool
    tls: bool
    tls_verify: bool


def config_from_env() -> QodConfig:
    """Build a config from ``QOD_*`` env vars, falling back to a local edge
    (127.0.0.1:31338) and the superuser ``admin`` against the ``acme`` tenant's
    ``bi`` pool."""
    return QodConfig(
        host=os.environ.get("QOD_HOST", "127.0.0.1"),
        port=int(os.environ.get("QOD_PORT", "31338")),
        user=os.environ.get("QOD_USER", "admin"),
        password=os.environ.get("QOD_PASSWORD", "admin"),
        tenant=os.environ.get("QOD_TENANT", "acme"),
        pool=os.environ.get("QOD_POOL", "bi"),
        superuser=_env_bool("QOD_SUPERUSER", True),
        tls=_env_bool("QOD_TLS", True),
        tls_verify=_env_bool("QOD_TLS_VERIFY", False),
    )


class QodClient:
    """A connected FlightSQL client. Use as a context manager or call
    :meth:`close` when done."""

    def __init__(self, cfg: QodConfig, conn: flight_sql.Connection):
        self._cfg = cfg
        self._conn = conn

    @classmethod
    def connect(cls, cfg: QodConfig) -> "QodClient":
        scheme = "grpc+tls" if cfg.tls else "grpc"
        uri = f"{scheme}://{cfg.host}:{cfg.port}"

        hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
        db_kwargs = {
            "username": cfg.user,
            "password": cfg.password,
            hdr + "tenant": cfg.tenant,
            hdr + "pool": cfg.pool,
        }
        if cfg.superuser:
            db_kwargs[hdr + "superuser"] = "true"
        # The edge ships an auto-generated self-signed cert; skip verification
        # unless the caller opted into a CA-signed cert with QOD_TLS_VERIFY.
        if cfg.tls and not cfg.tls_verify:
            db_kwargs[DatabaseOptions.TLS_SKIP_VERIFY.value] = "true"

        # autocommit=True: the edge runs each statement standalone and does not
        # support toggling autocommit off, so ask for it up front to avoid a
        # spurious DB-API compliance warning.
        conn = flight_sql.connect(uri=uri, db_kwargs=db_kwargs, autocommit=True)
        return cls(cfg, conn)

    def describe(self) -> str:
        proto = "grpc+tls" if self._cfg.tls else "grpc"
        su = " (superuser)" if self._cfg.superuser else ""
        return (
            f"{proto}://{self._cfg.host}:{self._cfg.port} as "
            f"{self._cfg.user}@{self._cfg.tenant}/{self._cfg.pool}{su}"
        )

    def query(self, sql: str) -> pa.Table:
        """Run one SQL statement and return the decoded Arrow table."""
        with self._conn.cursor() as cur:
            cur.execute(sql)
            return cur.fetch_arrow_table()

    def close(self) -> None:
        self._conn.close()

    def __enter__(self) -> "QodClient":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()


def row_to_dict(table: pa.Table, index: int) -> dict:
    """Convert one Arrow row into a plain, JSON-friendly dict. Decimal and other
    non-native values are coerced to their string form so ``json.dumps`` renders
    them cleanly."""
    out: dict = {}
    for name in table.column_names:
        value = table.column(name)[index].as_py()
        out[name] = value if isinstance(value, (int, float, str, bool, type(None))) else str(value)
    return out