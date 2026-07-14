import typer

from ._run import call

app = typer.Typer(help="DuckLake catalog browsing, time travel, and recovery.")

TENANT = typer.Argument(..., metavar="TENANT")
DB = typer.Argument(..., metavar="DB")
SCHEMA = typer.Argument(..., metavar="SCHEMA")
TABLE = typer.Argument(..., metavar="TABLE")


def _base(tenant: str, db: str) -> str:
    return f"/api/catalog/tenant/{tenant}/database/{db}"


def _at_most_one(**selectors) -> None:
    given = [name for name, value in selectors.items() if value is not None]
    if len(given) > 1:
        raise typer.BadParameter(f"{' and '.join(given)} are mutually exclusive")


@app.command()
def schemas(ctx: typer.Context, tenant: str = TENANT, db: str = DB):
    call(ctx, "GET", f"{_base(tenant, db)}/schemas")


@app.command()
def tables(ctx: typer.Context, tenant: str = TENANT, db: str = DB, schema: str = SCHEMA):
    call(ctx, "GET", f"{_base(tenant, db)}/schemas/{schema}/tables")


@app.command()
def describe(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    as_of: int = typer.Option(None, "--as-of", help="Snapshot id."),
    as_of_tag: str = typer.Option(None, "--as-of-tag"),
    as_of_ts: str = typer.Option(None, "--as-of-ts", help="ISO timestamp."),
):
    _at_most_one(as_of=as_of, as_of_tag=as_of_tag, as_of_ts=as_of_ts)
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}",
        params={"asOf": as_of, "asOfTag": as_of_tag, "asOfTs": as_of_ts},
    )


@app.command()
def snapshots(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    limit: int = typer.Option(None, "--limit"),
    before: int = typer.Option(None, "--before", help="Keyset pagination: snapshot id."),
    table: str = typer.Option(None, "--table", help="schema.table filter."),
):
    call(ctx, "GET", f"{_base(tenant, db)}/snapshots", params={"limit": limit, "before": before, "table": table})


@app.command()
def history(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    limit: int = typer.Option(None, "--limit"),
    before: int = typer.Option(None, "--before"),
    from_: str = typer.Option(None, "--from"),
    to: str = typer.Option(None, "--to"),
    operation: str = typer.Option(None, "--operation"),
    author: str = typer.Option(None, "--author"),
):
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/history",
        params={"limit": limit, "before": before, "from": from_, "to": to, "operation": operation, "author": author},
    )


@app.command()
def preview(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    as_of: int = typer.Option(None, "--as-of"),
    as_of_tag: str = typer.Option(None, "--as-of-tag"),
    as_of_ts: str = typer.Option(None, "--as-of-ts"),
    limit: int = typer.Option(None, "--limit"),
):
    _at_most_one(as_of=as_of, as_of_tag=as_of_tag, as_of_ts=as_of_ts)
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/preview",
        params={"asOf": as_of, "asOfTag": as_of_tag, "asOfTs": as_of_ts, "limit": limit},
    )


@app.command("data-diff")
def data_diff(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    from_: str = typer.Option(..., "--from", help="From snapshot selector."),
    to: str = typer.Option(..., "--to", help="To snapshot selector."),
    limit: int = typer.Option(None, "--limit"),
    cursor: str = typer.Option(None, "--cursor"),
    change_type: str = typer.Option(None, "--change-type"),
):
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/data-diff",
        params={"from": from_, "to": to, "limit": limit, "cursor": cursor, "changeType": change_type},
    )


@app.command("schema-diff")
def schema_diff(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    from_: str = typer.Option(..., "--from"),
    to: str = typer.Option(..., "--to"),
):
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/schema-diff",
        params={"from": from_, "to": to},
    )


@app.command()
def recoverable(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    limit: int = typer.Option(None, "--limit"),
):
    """Dropped tables still recoverable via undrop."""
    call(ctx, "GET", f"{_base(tenant, db)}/recoverable", params={"limit": limit})


@app.command()
def undrop(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    db: str = typer.Option(..., "--db"),
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
    as_name: str = typer.Option(None, "--as-name", help="Recover under a different name."),
    from_snapshot: int = typer.Option(None, "--from-snapshot"),
):
    body: dict = {"tenant": tenant, "tenantDb": db, "schema": schema, "table": table}
    if as_name is not None:
        body["asName"] = as_name
    if from_snapshot is not None:
        body["fromSnapshot"] = from_snapshot
    call(ctx, "POST", "/api/catalog/undrop", body=body)
