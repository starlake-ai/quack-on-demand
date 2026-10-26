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

A booting serve hands the same three create bodies to its own manager instead:
it writes them to the spec file named by QOD_SERVE_PROVISION, and the manager's
add-only ServeProvisionHook creates what is missing at boot and writes an
outcome file beside the spec. Attach mode (a manager already running) posts
them through REST with the caller's own credential.

Kept free of typer so it is unit-testable against respx with a plain RestClient.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

from .rest import ApiError, RestClient
from .serve_target import ServeTarget, composed_db_name


class ProvisionError(Exception):
    """A provisioning step that failed in a way the user can act on.

    `manual` names the equivalent hand-run commands, one per line, so the caller
    can finish by hand. `hint` is advice that is no such command (where to look,
    or a collision's way out, where a create would collide again). The manager is
    deliberately left running when one of these is raised: every step is
    ensure-semantics, so the next `qod serve` resumes where this one stopped.
    """

    def __init__(self, step: str, detail: str, manual: str = "", hint: str = ""):
        self.step = step
        self.detail = detail
        self.manual = manual
        self.hint = hint
        message = f"{step}: {detail}"
        for extra in (manual, hint):
            if extra:
                message += f" ({extra})"
        super().__init__(message)


# The spec file a booting serve writes in its state directory, and the outcome file
# the manager's ServeProvisionHook writes beside it.
SPEC_FILE = "serve-provision.json"
OUTCOME_FILE = "serve-provision.outcome.json"

# The fix every collision names (a database name or a pool name already taken): both
# this module (as the refusal's hint) and the boot hook (in its reason) give it, and
# no create-by-hand command follows, since it would collide again.
ANOTHER_NAME = "serve it under another name"


def tenant_body(tenant: str) -> dict:
    """POST /api/tenant/create's body (a TenantRequest)."""
    return {"id": tenant, "displayName": tenant, "authProvider": "db", "authConfig": {}}


def database_body(tenant: str, target: ServeTarget) -> dict:
    """POST /api/database/create's body (a TenantDbRequest). `name` is the suffix:
    the server composes `<tenant>_<suffix>`."""
    return {
        "tenant": tenant,
        "name": target.name,
        "kind": target.kind,
        "metastore": dict(target.metastore),
        "dataPath": target.data_path,
        "objectStore": dict(target.object_store),
        "initSql": target.init_sql,
    }


def pool_body(tenant: str, db_full: str, pool: str, size: int) -> dict:
    """POST /api/pool/create's body (a CreatePoolRequest): SIZE dual nodes."""
    return {
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
    }


def tenant_manual(tenant: str) -> str:
    return f"qod tenant create {tenant}"


def database_manual(tenant: str, target: ServeTarget) -> str:
    return (
        f"qod database create --tenant {tenant} --name {target.name} --kind {target.kind} "
        "--data-path <path> --init-sql <sql>"
    )


def pool_manual(tenant: str, db_full: str, pool: str, size: int) -> str:
    return (
        f"qod pool create --tenant {tenant} --db {db_full} --pool {pool} "
        f"--size {size} --dual {size}"
    )


def finish_by_hand(step: str, tenant: str, target: ServeTarget, pool: str, size: int) -> str:
    """The hand-run command for the create STEP a provisioning outcome names (tenant,
    database or pool): the one the REST path prints for that step; "" for any other,
    `spec` included."""
    manuals = {
        "tenant": tenant_manual(tenant),
        "database": database_manual(tenant, target),
        "pool": pool_manual(tenant, composed_db_name(tenant, target.name), pool, size),
    }
    return manuals.get(step, "")


def provision_by_hand(tenant: str, target: ServeTarget, pool: str, size: int) -> str:
    """All three hand-run commands, one per line, in the order they must run: for a
    manager that did not provision at all."""
    return "\n".join(
        finish_by_hand(step, tenant, target, pool, size) for step in ("tenant", "database", "pool")
    )


def provision_spec(tenant: str, target: ServeTarget, pool: str, size: int) -> dict:
    """The spec file's content: the three create bodies attach mode posts, under the
    keys ServeProvisionHook reads."""
    return {
        "tenant": tenant_body(tenant),
        "database": database_body(tenant, target),
        "pool": pool_body(tenant, composed_db_name(tenant, target.name), pool, size),
    }


def write_spec(path: Path, spec: dict) -> None:
    """Write SPEC to PATH, readable by its owner only (the database body can carry
    object-store credentials), replacing any earlier file."""
    tmp = path.with_name(path.name + ".tmp")
    tmp.unlink(missing_ok=True)
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(spec, out, indent=2)
        os.replace(tmp, path)
    finally:
        # A failed write leaves no partial copy of the credentials behind.
        tmp.unlink(missing_ok=True)


def outcome_path(spec_path: Path) -> Path:
    """Where ServeProvisionHook writes its outcome: beside the spec."""
    return spec_path.with_name(OUTCOME_FILE)


def _read_outcome(path: Path) -> dict | None:
    """The outcome at PATH, or None while the manager has not written it. The hook
    writes it atomically (a temporary file, then a rename), so a file that exists is
    complete; content that is not an outcome is a ProvisionError."""
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    try:
        outcome = json.loads(raw)
    except ValueError:
        outcome = None
    if not isinstance(outcome, dict) or outcome.get("status") not in ("ok", "failed"):
        raise ProvisionError(
            "read provisioning outcome",
            f"{path} holds no outcome this CLI understands",
            hint="the manager log above says why",
        )
    return outcome


def wait_outcome(
    path: Path,
    timeout_s: float,
    interval_s: float = 0.5,
    sleep=time.sleep,
    now=time.monotonic,
) -> dict | None:
    """Block until ServeProvisionHook wrote its outcome to PATH and return it:
    {"status": "ok", "created": [...]} or
    {"status": "failed", "step": "spec|tenant|database|pool", "reason": "..."}.
    None once TIMEOUT_S passed with no outcome written: a manager release without
    the hook never writes one, and the caller says so. `sleep` and `now` are
    injected so tests never wall-clock."""
    deadline = now() + timeout_s
    outcome = _read_outcome(path)
    while outcome is None and now() < deadline:
        sleep(interval_s)
        outcome = _read_outcome(path)
    return outcome


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
        hint="the manager log above says why; `qod status` reports what is up",
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
        client.request("POST", "/api/tenant/create", body=tenant_body(tenant))
    except ApiError as exc:
        raise ProvisionError("create tenant", str(exc), tenant_manual(tenant))
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
            hint=f"{ANOTHER_NAME}: qod serve <target> --name <other>",
        )
    try:
        client.request("POST", "/api/database/create", body=database_body(tenant, target))
    except ApiError as exc:
        raise ProvisionError("create database", str(exc), database_manual(tenant, target))
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
        # Re-check the deadline before sleeping a full interval: without this,
        # a poll that comes back not-yet-healthy right before the deadline
        # still sleeps the whole interval before the loop condition notices,
        # overshooting by up to interval_s.
        if now() >= deadline:
            break
        sleep(interval_s)
    return False


def ensure_pool(client: RestClient, tenant: str, db_full: str, pool: str, size: int) -> bool:
    """True when created, False when it already existed.

    One DUAL node by default: `duckdb-file` ATTACHes read-write and DuckDB holds a
    single-writer lock on the file, so more than one node on the same file cannot
    start. A single dual node still serves many concurrent clients.

    A pool of that name serving another database of the tenant is refused, as the
    boot hook refuses it: the edge resolves (tenant, pool) at the handshake, so pool
    names are unique per tenant, and the fix is another name, not a create.
    """
    try:
        existing = client.request("GET", "/api/pool/list") or {}
    except ApiError as exc:
        raise ProvisionError("list pools", str(exc), "qod pool list")
    for row in existing.get("pools", []):
        if (row.get("tenant"), row.get("pool")) != (tenant, pool):
            continue
        if row.get("tenantDb") == db_full:
            return False
        raise ProvisionError(
            "ensure pool",
            f"pool {pool!r} already serves database {row.get('tenantDb')!r}",
            hint=f"{ANOTHER_NAME}: qod serve <target> --pool <other>",
        )
    try:
        client.request("POST", "/api/pool/create", body=pool_body(tenant, db_full, pool, size))
    except ApiError as exc:
        raise ProvisionError("create pool", str(exc), pool_manual(tenant, db_full, pool, size))
    return True
