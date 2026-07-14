import typer

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
def list_(ctx: typer.Context, tenant: str = TENANT, db: str = DB):
    call(ctx, "GET", _base(tenant, db))


@app.command()
def get(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "GET", f"{_base(tenant, db)}/{alias}")


@app.command()
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
def delete(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "DELETE", f"{_base(tenant, db)}/{alias}")


@secret_app.command("list")
def secret_list(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "GET", f"{_base(tenant, db)}/{alias}/secrets")


@secret_app.command("set")
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
def secret_delete(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = ALIAS,
    name: str = typer.Argument(..., metavar="NAME"),
):
    call(ctx, "DELETE", f"{_base(tenant, db)}/{alias}/secrets/{name}")
