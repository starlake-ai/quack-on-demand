import typer

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
