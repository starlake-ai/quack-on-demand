from typing import Optional

import typer

from ..output import render
from ..registry import covers
from ._run import call

app = typer.Typer(help="Node lifecycle and statement inspection.")
statement_app = typer.Typer(help="Statement control.")

TENANT = typer.Option(..., "--tenant")
DB = typer.Option(..., "--db", help="Tenant database name.")
POOL = typer.Option(..., "--pool")
NODE = typer.Option(..., "--node-id")


def _key(tenant: str, db: str, pool: str, node_id: str) -> dict:
    return {"tenant": tenant, "tenantDb": db, "pool": pool, "nodeId": node_id}


@app.command("list")
def list_(
    ctx: typer.Context,
    tenant: Optional[str] = typer.Option(None, "--tenant", help="Only this tenant's nodes."),
    pool: Optional[str] = typer.Option(None, "--pool", help="Only this pool's nodes."),
):
    """One row per node, with the fleet server hosting it (server is empty off the fleet runtime)."""
    data = call(ctx, "GET", "/api/pool/list", quiet=True)
    rows = [
        {"node": n.get("nodeId"), "tenant": p.get("tenant"), "db": p.get("tenantDb"), "pool": p.get("pool"),
         "role": n.get("role"), "address": f"{n.get('host')}:{n.get('port')}",
         "server": n.get("serverName"), "serverState": n.get("serverState"),
         "healthy": n.get("healthy"), "quarantined": n.get("quarantined"), "inFlight": n.get("inFlight")}
        for p in (data or {}).get("pools", [])
        if (tenant is None or p.get("tenant") == tenant) and (pool is None or p.get("pool") == pool)
        for n in p.get("nodes", [])
    ]
    render(rows, ctx.obj.json_output)


@app.command()
@covers(
    "POST",
    "/api/node/quarantine",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "nodeId": "--node-id"},
)
def quarantine(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, node_id: str = NODE):
    call(ctx, "POST", "/api/node/quarantine", body=_key(tenant, db, pool, node_id))


@app.command()
@covers(
    "POST",
    "/api/node/unquarantine",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "nodeId": "--node-id"},
)
def unquarantine(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, node_id: str = NODE):
    call(ctx, "POST", "/api/node/unquarantine", body=_key(tenant, db, pool, node_id))


@app.command()
@covers(
    "POST",
    "/api/node/restart",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "nodeId": "--node-id"},
)
def restart(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, node_id: str = NODE):
    call(ctx, "POST", "/api/node/restart", body=_key(tenant, db, pool, node_id))


@app.command("set-max-concurrent")
@covers(
    "POST",
    "/api/node/setMaxConcurrent",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "nodeId": "--node-id", "max": "--max"},
)
def set_max_concurrent(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    pool: str = POOL,
    node_id: str = NODE,
    max_: int = typer.Option(..., "--max"),
):
    call(ctx, "POST", "/api/node/setMaxConcurrent", body={**_key(tenant, db, pool, node_id), "max": max_})


@app.command()
@covers("GET", "/api/node/statements", {"limit": "--limit"})
def statements(ctx: typer.Context, limit: int = typer.Option(None, "--limit")):
    """Recent statement history, newest first."""
    call(ctx, "GET", "/api/node/statements", params={"limit": limit})


@app.command("active-statements")
@covers("GET", "/api/node/active-statements")
def active_statements(ctx: typer.Context):
    call(ctx, "GET", "/api/node/active-statements")


@statement_app.command()
@covers("POST", "/api/statement/kill", {"id": "ID"})
def kill(ctx: typer.Context, statement_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/statement/kill", body={"id": statement_id})
