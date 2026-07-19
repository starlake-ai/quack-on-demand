import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Snapshot tags (create, delete, protect).")

TENANT = typer.Option(..., "--tenant")
DB = typer.Option(..., "--db")
NAME = typer.Option(..., "--name")


@app.command()
@covers(
    "POST",
    "/api/catalog/tag/create",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "name": "--name",
        "snapshotId": "--snapshot-id",
        "isProtected": "--protected",
    },
)
def create(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    name: str = NAME,
    snapshot_id: int = typer.Option(..., "--snapshot-id"),
    protected: bool = typer.Option(False, "--protected"),
):
    call(
        ctx,
        "POST",
        "/api/catalog/tag/create",
        body={"tenant": tenant, "tenantDb": db, "name": name, "snapshotId": snapshot_id, "isProtected": protected},
    )


@app.command()
@covers("POST", "/api/catalog/tag/delete", {"tenant": "--tenant", "tenantDb": "--db", "name": "--name"})
def delete(ctx: typer.Context, tenant: str = TENANT, db: str = DB, name: str = NAME):
    call(ctx, "POST", "/api/catalog/tag/delete", body={"tenant": tenant, "tenantDb": db, "name": name})


@app.command()
@covers(
    "POST",
    "/api/catalog/tag/protect",
    {"tenant": "--tenant", "tenantDb": "--db", "name": "--name", "isProtected": "--protected/--unprotected"},
)
def protect(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    name: str = NAME,
    protected: bool = typer.Option(..., "--protected/--unprotected"),
):
    call(
        ctx,
        "POST",
        "/api/catalog/tag/protect",
        body={"tenant": tenant, "tenantDb": db, "name": name, "isProtected": protected},
    )
