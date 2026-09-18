"""Idempotent control-plane provisioning for `qod serve`.

Every helper is ensure-semantics: list, create only what is missing, never
delete. That is what makes a second `qod serve ./other.duckdb` add a database
beside the first instead of replacing it, and it is why neither existing
bootstrap mechanism could be used here:

  - DemoBootstrapHook.applyIfFresh skips entirely once the store holds ANY
    tenant, so it cannot add a second database later.
  - ManifestImporter.apply is delete-then-upsert: siblings under a tenant named
    in the YAML are deleted, so serving a second file through it would destroy
    the first.

Kept free of typer so it is unit-testable against respx with a plain RestClient.
"""

from __future__ import annotations

import time

from .rest import ApiError, RestClient
from .serve_target import ServeTarget, composed_db_name


class ProvisionError(Exception):
    """A provisioning step that failed in a way the user can act on.

    `manual` names the equivalent hand-run command so the caller can finish by
    hand. The manager is deliberately left running when one of these is raised:
    every step is ensure-semantics, so the next `qod serve` resumes where this
    one stopped.
    """

    def __init__(self, step: str, detail: str, manual: str = ""):
        self.step = step
        self.detail = detail
        self.manual = manual
        message = f"{step}: {detail}"
        if manual:
            message += f" ({manual})"
        super().__init__(message)


def wait_ready(
    client: RestClient,
    timeout_s: float = 180.0,
    interval_s: float = 0.5,
    sleep=time.sleep,
    now=time.monotonic,
) -> None:
    """Block until GET /ready answers 200.

    /ready is 503 until Postgres is reachable and Liquibase has run, which on a
    first boot against a fresh embedded Postgres includes initdb plus the full
    migration set. `sleep` and `now` are injected so tests never wall-clock.
    """
    deadline = now() + timeout_s
    last = "no response yet"
    while now() < deadline:
        try:
            client.request("GET", "/ready")
            return
        except ApiError as exc:
            last = str(exc)
        sleep(interval_s)
    raise ProvisionError(
        "wait for manager",
        f"/ready never went green within {timeout_s:.0f}s (last: {last})",
        "the manager log above says why; `qod status` reports what is up",
    )


def ensure_tenant(client: RestClient, tenant: str) -> bool:
    """True when created, False when it already existed."""
    # the server normalizes tenant ids to lowercase slugs on create; match and
    # create on the normalized form so an exact-case list match never misses.
    tenant = tenant.lower()
    try:
        existing = client.request("GET", "/api/tenant/list") or {}
    except ApiError as exc:
        raise ProvisionError("list tenants", str(exc), "qod tenant list")
    for row in existing.get("tenants", []):
        if tenant in (row.get("name"), row.get("id")):
            return False
    try:
        client.request(
            "POST",
            "/api/tenant/create",
            body={"id": tenant, "displayName": tenant, "authProvider": "db", "authConfig": {}},
        )
    except ApiError as exc:
        raise ProvisionError("create tenant", str(exc), f"qod tenant create {tenant}")
    return True


def ensure_database(client: RestClient, tenant: str, target: ServeTarget) -> bool:
    """True when created, False when an identical row already existed.

    `database/create` takes the tenant-db SUFFIX while `database/list` returns the
    COMPOSED `<tenant>_<suffix>` name (Names.normalizeTenantDbName), so the match
    runs against the composed form.
    """
    full = composed_db_name(tenant, target.name)
    try:
        existing = client.request("GET", "/api/database/list", params={"tenant": tenant}) or {}
    except ApiError as exc:
        raise ProvisionError("list databases", str(exc), f"qod database list --tenant {tenant}")
    for row in existing.get("tenantDbs", []):
        if row.get("name") != full:
            continue
        current = row.get("dataPath") or ""
        current_init_sql = row.get("initSql") or ""
        if (
            current == target.data_path
            and row.get("kind") == target.kind
            and current_init_sql == target.init_sql
        ):
            return False
        if current == target.data_path and row.get("kind") == target.kind:
            detail = (
                f"database {full!r} already exists with the same dataPath and kind, but its "
                "views point at different data (initSql differs)"
            )
        else:
            detail = (
                f"database {full!r} already serves kind={row.get('kind')!r} "
                f"dataPath={current!r}, not kind={target.kind!r} dataPath={target.data_path!r}"
            )
        raise ProvisionError(
            "ensure database",
            detail,
            "serve it under another name: qod serve <target> --name <other>",
        )
    try:
        client.request(
            "POST",
            "/api/database/create",
            body={
                "tenant": tenant,
                "name": target.name,
                "kind": target.kind,
                "metastore": dict(target.metastore),
                "dataPath": target.data_path,
                "objectStore": dict(target.object_store),
                "initSql": target.init_sql,
            },
        )
    except ApiError as exc:
        raise ProvisionError(
            "create database",
            str(exc),
            f"qod database create --tenant {tenant} --name {target.name} --kind {target.kind} "
            "--data-path <path> --init-sql <sql>",
        )
    return True


def wait_node_routable(
    client: RestClient,
    tenant: str,
    db_full: str,
    pool: str,
    timeout_s: float = 60.0,
    interval_s: float = 1.0,
    sleep=time.sleep,
    now=time.monotonic,
) -> bool:
    """Poll /api/pool/list until the (tenant, tenantDb, pool) row has at least one
    healthy node, or `timeout_s` elapses.

    On a fresh create the pool's node is usually up by the time this runs, but on
    a RESTART the pool row already exists while its respawned node may still be
    seconds from passing the health probe - printing the banner's connect strings
    before that moment means the first query briefly fails. An ApiError mid-poll
    (a transient hiccup, not a real failure) is swallowed and retried until the
    deadline, same posture as `wait_ready` above. `sleep`/`now` are injected so
    tests never wall-clock.
    """
    deadline = now() + timeout_s
    while now() < deadline:
        try:
            existing = client.request("GET", "/api/pool/list") or {}
            for row in existing.get("pools", []):
                if (row.get("tenant"), row.get("tenantDb"), row.get("pool")) != (
                    tenant, db_full, pool,
                ):
                    continue
                if any(n.get("healthy") for n in row.get("nodes", [])):
                    return True
        except ApiError:
            pass
        sleep(interval_s)
    return False


def ensure_pool(client: RestClient, tenant: str, db_full: str, pool: str, size: int) -> bool:
    """True when created, False when it already existed.

    One DUAL node by default: `duckdb-file` ATTACHes read-write and DuckDB holds a
    single-writer lock on the file, so more than one node on the same file cannot
    start. A single dual node still serves many concurrent clients.
    """
    try:
        existing = client.request("GET", "/api/pool/list") or {}
    except ApiError as exc:
        raise ProvisionError("list pools", str(exc), "qod pool list")
    for row in existing.get("pools", []):
        if (row.get("tenant"), row.get("tenantDb"), row.get("pool")) == (tenant, db_full, pool):
            return False
    try:
        client.request(
            "POST",
            "/api/pool/create",
            body={
                "tenant": tenant,
                "tenantDb": db_full,
                "pool": pool,
                "size": size,
                "roleDistribution": {"writeonly": 0, "readonly": 0, "dual": size},
                "idleTimeoutSec": -1,
                "maxConcurrentPerNode": 0,
                "disabled": False,
                "cpu": "",
                "memory": "",
                "podTemplateYaml": "",
                "startSuspended": False,
                "lockdown": "inherit",
            },
        )
    except ApiError as exc:
        raise ProvisionError(
            "create pool",
            str(exc),
            f"qod pool create --tenant {tenant} --db {db_full} --pool {pool} "
            f"--size {size} --dual {size}",
        )
    return True
