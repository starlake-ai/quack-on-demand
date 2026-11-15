#!/usr/bin/env python3
"""Creates the part 2 users, roles, grants and row policies on the QoD stack.

Usage: uv run --no-project --with adbc-driver-flightsql --with pyarrow python -I qod_users.py <p2>

Runs, as the stack's superuser (admin, superuser=true header) through Flight SQL (ADBC, TLS,
the edge's self-signed certificate not verified, as the qod CLI does) on tenant acme, pool bi,
the statements below, the SQL admin dialect's except the first. The tables must exist (run
after qod_load.sql). Passwords are read from <p2>/secrets/<name>.txt and substituted inside
this process only: never printed, never written to disk. Prints one `ok <n>` per statement;
an error names the statement's number only (its text can hold a password).
Not idempotent: it creates every object, so a second run stops at statement 1 (the table
exists); run it once on a fresh stack, or drop the objects first.
"""
import os
import sys

import adbc_driver_flightsql.dbapi as flight
from adbc_driver_flightsql import DatabaseOptions

P2 = sys.argv[1]
HOST, PORT, TENANT, POOL = "127.0.0.1", 27338, "acme", "bi"

STATEMENTS = [
    "CREATE TABLE acme_lake.main.other (id BIGINT, secret VARCHAR)",
    "CREATE ROLE reader",
    "GRANT SELECT ON acme_lake.main.pages_1m TO ROLE reader",
    "GRANT SELECT ON acme_lake.main.pages_10m TO ROLE reader",
    "GRANT SELECT ON acme_lake.main.pages_100m_drain TO ROLE reader",
    "CREATE ROLE reader_rls10",
    "GRANT SELECT ON acme_lake.main.pages_1m TO ROLE reader_rls10",
    "GRANT SELECT ON acme_lake.main.pages_10m TO ROLE reader_rls10",
    "CREATE ROW POLICY ON acme_lake.main.pages_1m FOR ROLE reader_rls10 USING (f10 = 0)",
    "CREATE ROW POLICY ON acme_lake.main.pages_10m FOR ROLE reader_rls10 USING (f10 = 0)",
    "CREATE ROLE reader_rls1",
    "GRANT SELECT ON acme_lake.main.pages_1m TO ROLE reader_rls1",
    "GRANT SELECT ON acme_lake.main.pages_10m TO ROLE reader_rls1",
    "CREATE ROW POLICY ON acme_lake.main.pages_1m FOR ROLE reader_rls1 USING (f100 = 0)",
    "CREATE ROW POLICY ON acme_lake.main.pages_10m FOR ROLE reader_rls1 USING (f100 = 0)",
    "CREATE ROLE reader_other",
    "GRANT SELECT ON acme_lake.main.other TO ROLE reader_other",
    "CREATE USER plain PASSWORD '{plain}' ROLES reader GROUPS qod_all_pools",
    "CREATE USER rls10 PASSWORD '{rls10}' ROLES reader_rls10 GROUPS qod_all_pools",
    "CREATE USER rls1 PASSWORD '{rls1}' ROLES reader_rls1 GROUPS qod_all_pools",
    "CREATE USER other PASSWORD '{other}' ROLES reader_other GROUPS qod_all_pools",
]


def secret(name):
    with open(os.path.join(P2, "secrets", f"{name}.txt")) as f:
        value = f.read().strip()
    if not value or "'" in value:
        sys.exit(f"secret {name} is empty or holds a quote")
    return value


passwords = {n: secret(n) for n in ("plain", "rls10", "rls1", "other")}
hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
conn = flight.connect(
    f"grpc+tls://{HOST}:{PORT}",
    db_kwargs={
        "username": "admin",
        "password": secret("admin"),
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
        hdr + "tenant": TENANT,
        hdr + "pool": POOL,
        hdr + "superuser": "true",
    },
    autocommit=True,
)
failed = 0
with conn.cursor() as cur:
    for n, template in enumerate(STATEMENTS, start=1):
        sql = template.format(**passwords) if "PASSWORD" in template else template
        try:
            cur.execute(sql)
            try:
                cur.fetchall()
            except Exception:  # noqa: BLE001 - a statement with no result set
                pass
            print(f"ok {n}", flush=True)
        except Exception as exc:  # noqa: BLE001 - report and stop
            failed = n
            if "PASSWORD" in template:
                print(f"error {n}: (message withheld: the statement holds a password)", flush=True)
            else:
                print(f"error {n}: {type(exc).__name__}: {str(exc).splitlines()[0][:300]}", flush=True)
            break
conn.close()
sys.exit(1 if failed else 0)
