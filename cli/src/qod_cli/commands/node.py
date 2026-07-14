import typer

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
def quarantine(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, node_id: str = NODE):
    call(ctx, "POST", "/api/node/quarantine", body=_key(tenant, db, pool, node_id))


@app.command()
def unquarantine(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, node_id: str = NODE):
    call(ctx, "POST", "/api/node/unquarantine", body=_key(tenant, db, pool, node_id))


@app.command()
def restart(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, node_id: str = NODE):
    call(ctx, "POST", "/api/node/restart", body=_key(tenant, db, pool, node_id))


@app.command("set-max-concurrent")
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
def statements(ctx: typer.Context, limit: int = typer.Option(None, "--limit")):
    """Recent statement history, newest first."""
    call(ctx, "GET", "/api/node/statements", params={"limit": limit})


@app.command("active-statements")
def active_statements(ctx: typer.Context):
    call(ctx, "GET", "/api/node/active-statements")


@statement_app.command()
def kill(ctx: typer.Context, statement_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/statement/kill", body={"id": statement_id})
