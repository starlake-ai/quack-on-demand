import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Federated sources per (tenant, tenant-db).")
secret_app = typer.Typer(help="Secrets referenced by a federated source's setup SQL.")
app.add_typer(secret_app, name="secret")

TENANT = typer.Argument(..., metavar="TENANT")
DB = typer.Argument(..., metavar="DB")
ALIAS = typer.Argument(..., metavar="ALIAS")


def _base(tenant: str, db: str) -> str:
    return f"/api/tenants/{tenant}/tenant-dbs/{db}/federated-sources"


@app.command("list")
@covers(
    "GET",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources",
    {"tenant": "TENANT", "tenantDb": "DB"},
)
def list_(ctx: typer.Context, tenant: str = TENANT, db: str = DB):
    call(ctx, "GET", _base(tenant, db))


@app.command()
@covers(
    "GET",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS"},
)
def get(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "GET", f"{_base(tenant, db)}/{alias}")


@app.command()
@covers(
    "POST",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "--alias",
        "setupSql": "--setup-sql",
        "description": "--description",
        "disabled": "--disabled",
    },
)
def create(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = typer.Option(..., "--alias"),
    setup_sql: str = typer.Option(..., "--setup-sql"),
    description: str = typer.Option(None, "--description"),
    disabled: bool = typer.Option(False, "--disabled"),
):
    body: dict = {"alias": alias, "setupSql": setup_sql, "disabled": disabled}
    if description is not None:
        body["description"] = description
    call(ctx, "POST", _base(tenant, db), body=body)


@app.command()
@covers(
    "DELETE",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS"},
)
def delete(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "DELETE", f"{_base(tenant, db)}/{alias}")


@secret_app.command("list")
@covers(
    "GET",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS"},
)
def secret_list(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "GET", f"{_base(tenant, db)}/{alias}/secrets")


@secret_app.command("set")
@covers(
    "PUT",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "ALIAS",
        "name": "--name",
        "value": "--value",
        "externalRef": "--external-ref",
    },
)
def secret_set(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = ALIAS,
    name: str = typer.Option(..., "--name"),
    value: str = typer.Option(None, "--value", help="Inline value stored in Postgres."),
    external_ref: str = typer.Option(None, "--external-ref", help="e.g. env:PGPASS."),
):
    if (value is None) == (external_ref is None):
        raise typer.BadParameter("pass exactly one of --value / --external-ref")
    body: dict = {"name": name}
    if value is not None:
        body["value"] = value
    if external_ref is not None:
        body["externalRef"] = external_ref
    call(ctx, "PUT", f"{_base(tenant, db)}/{alias}/secrets", body=body)


@secret_app.command("delete")
@covers(
    "DELETE",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets/{name}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS", "name": "NAME"},
)
def secret_delete(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = ALIAS,
    name: str = typer.Argument(..., metavar="NAME"),
):
    call(ctx, "DELETE", f"{_base(tenant, db)}/{alias}/secrets/{name}")
