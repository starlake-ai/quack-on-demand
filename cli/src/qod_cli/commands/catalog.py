import typer

from ..registry import covers
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


def _refused_with_iceberg(**flags) -> None:
    given = [name for name, value in flags.items() if value is not None]
    if given:
        verb = "is" if len(given) == 1 else "are"
        raise typer.BadParameter(f"{' and '.join(given)} {verb} not supported with --iceberg")


def _snapshot_id(value: str | None, flag: str) -> int | None:
    """DuckLake path only: `--before`/`--as-of` used to be declared `int`; kept
    as `str` now so a 19-digit Iceberg snapshot id round-trips unchanged, but the
    DuckLake callers still get the same integer-or-refuse validation as before."""
    if value is None:
        return None
    if not value.isdigit():
        raise typer.BadParameter(f"{flag} must be a snapshot id (integer)")
    return int(value)


ICEBERG = typer.Option(None, "--iceberg", help="Target an attached iceberg_rest source by alias.")


@app.command()
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas",
    {"tenant": "TENANT", "tenantDb": "DB"},
)
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/iceberg/{alias}/schemas",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "--iceberg"},
)
def schemas(ctx: typer.Context, tenant: str = TENANT, db: str = DB, iceberg: str = ICEBERG):
    if iceberg:
        call(ctx, "GET", f"{_base(tenant, db)}/iceberg/{iceberg}/schemas")
    else:
        call(ctx, "GET", f"{_base(tenant, db)}/schemas")


@app.command()
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables",
    {"tenant": "TENANT", "tenantDb": "DB", "schema": "SCHEMA"},
)
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/iceberg/{alias}/schemas/{schema}/tables",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "--iceberg", "schema": "SCHEMA"},
)
def tables(
    ctx: typer.Context, tenant: str = TENANT, db: str = DB, schema: str = SCHEMA, iceberg: str = ICEBERG
):
    if iceberg:
        call(ctx, "GET", f"{_base(tenant, db)}/iceberg/{iceberg}/schemas/{schema}/tables")
    else:
        call(ctx, "GET", f"{_base(tenant, db)}/schemas/{schema}/tables")


@app.command()
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "schema": "SCHEMA",
        "table": "TABLE",
        "asOf": "--as-of",
        "asOfTag": "--as-of-tag",
        "asOfTs": "--as-of-ts",
    },
)
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/iceberg/{alias}/schemas/{schema}/tables/{table}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "--iceberg", "schema": "SCHEMA", "table": "TABLE"},
)
def describe(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    as_of: str = typer.Option(None, "--as-of", help="Snapshot id."),
    as_of_tag: str = typer.Option(None, "--as-of-tag"),
    as_of_ts: str = typer.Option(None, "--as-of-ts", help="ISO timestamp."),
    iceberg: str = ICEBERG,
):
    if iceberg:
        _refused_with_iceberg(**{"--as-of": as_of, "--as-of-tag": as_of_tag, "--as-of-ts": as_of_ts})
        call(ctx, "GET", f"{_base(tenant, db)}/iceberg/{iceberg}/schemas/{schema}/tables/{table}")
        return
    _at_most_one(as_of=as_of, as_of_tag=as_of_tag, as_of_ts=as_of_ts)
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}",
        params={"asOf": _snapshot_id(as_of, "--as-of"), "asOfTag": as_of_tag, "asOfTs": as_of_ts},
    )


@app.command()
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/snapshots",
    {"tenant": "TENANT", "tenantDb": "DB", "limit": "--limit", "before": "--before", "table": "--table"},
)
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
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/history",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "schema": "SCHEMA",
        "table": "TABLE",
        "limit": "--limit",
        "before": "--before",
        "from": "--from",
        "to": "--to",
        "operation": "--operation",
        "author": "--author",
    },
)
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/iceberg/{alias}/schemas/{schema}/tables/{table}/history",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "--iceberg",
        "schema": "SCHEMA",
        "table": "TABLE",
        "limit": "--limit",
        "before": "--before",
        "operation": "--operation",
    },
)
def history(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    limit: int = typer.Option(None, "--limit"),
    before: str = typer.Option(None, "--before"),
    from_: str = typer.Option(None, "--from"),
    to: str = typer.Option(None, "--to"),
    operation: str = typer.Option(None, "--operation"),
    author: str = typer.Option(None, "--author"),
    iceberg: str = ICEBERG,
):
    if iceberg:
        _refused_with_iceberg(**{"--from": from_, "--to": to, "--author": author})
        call(
            ctx,
            "GET",
            f"{_base(tenant, db)}/iceberg/{iceberg}/schemas/{schema}/tables/{table}/history",
            params={"limit": limit, "before": before, "operation": operation},
        )
        return
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/history",
        params={
            "limit": limit,
            "before": _snapshot_id(before, "--before"),
            "from": from_,
            "to": to,
            "operation": operation,
            "author": author,
        },
    )


@app.command()
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/preview",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "schema": "SCHEMA",
        "table": "TABLE",
        "asOf": "--as-of",
        "asOfTag": "--as-of-tag",
        "asOfTs": "--as-of-ts",
        "limit": "--limit",
    },
)
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/iceberg/{alias}/schemas/{schema}/tables/{table}/preview",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "--iceberg",
        "schema": "SCHEMA",
        "table": "TABLE",
        "asOf": "--as-of",
        "asOfTag": "--as-of-tag (refused with --iceberg; use --as-of or --as-of-ts)",
        "asOfTs": "--as-of-ts",
        "limit": "--limit",
    },
)
def preview(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    schema: str = SCHEMA,
    table: str = TABLE,
    as_of: str = typer.Option(None, "--as-of"),
    as_of_tag: str = typer.Option(None, "--as-of-tag"),
    as_of_ts: str = typer.Option(None, "--as-of-ts"),
    limit: int = typer.Option(None, "--limit"),
    iceberg: str = ICEBERG,
):
    if iceberg:
        _refused_with_iceberg(**{"--as-of-tag": as_of_tag})
        _at_most_one(as_of=as_of, as_of_ts=as_of_ts)
        call(
            ctx,
            "GET",
            f"{_base(tenant, db)}/iceberg/{iceberg}/schemas/{schema}/tables/{table}/preview",
            params={"asOf": as_of, "asOfTs": as_of_ts, "limit": limit},
        )
        return
    _at_most_one(as_of=as_of, as_of_tag=as_of_tag, as_of_ts=as_of_ts)
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/preview",
        params={"asOf": _snapshot_id(as_of, "--as-of"), "asOfTag": as_of_tag, "asOfTs": as_of_ts, "limit": limit},
    )


@app.command("data-diff")
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/data-diff",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "schema": "SCHEMA",
        "table": "TABLE",
        "from": "--from",
        "to": "--to",
        "limit": "--limit",
        "cursor": "--cursor",
        "changeType": "--change-type",
    },
)
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/iceberg/{alias}/schemas/{schema}/tables/{table}/data-diff",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "--iceberg",
        "schema": "SCHEMA",
        "table": "TABLE",
        "from": "--from",
        "to": "--to",
        "limit": "--limit",
        "changeType": "--change-type (added|removed for iceberg)",
    },
)
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
    iceberg: str = ICEBERG,
):
    if iceberg:
        _refused_with_iceberg(**{"--cursor": cursor})
        call(
            ctx,
            "GET",
            f"{_base(tenant, db)}/iceberg/{iceberg}/schemas/{schema}/tables/{table}/data-diff",
            params={"from": from_, "to": to, "limit": limit, "changeType": change_type},
        )
        return
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/schemas/{schema}/tables/{table}/data-diff",
        params={"from": from_, "to": to, "limit": limit, "cursor": cursor, "changeType": change_type},
    )


@app.command("schema-diff")
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/schemas/{schema}/tables/{table}/schema-diff",
    {"tenant": "TENANT", "tenantDb": "DB", "schema": "SCHEMA", "table": "TABLE", "from": "--from", "to": "--to"},
)
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
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/tags",
    {"tenant": "TENANT", "tenantDb": "DB"},
)
def tags(ctx: typer.Context, tenant: str = TENANT, db: str = DB):
    """List snapshot tags of the tenant-db (see `tag create`/`delete`/`protect` to manage them)."""
    call(ctx, "GET", f"{_base(tenant, db)}/tags")


@app.command()
@covers(
    "GET",
    "/api/catalog/tenant/{tenant}/database/{tenantDb}/recoverable",
    {"tenant": "TENANT", "tenantDb": "DB", "limit": "--limit"},
)
def recoverable(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    limit: int = typer.Option(None, "--limit"),
):
    """Dropped tables still recoverable via undrop."""
    call(ctx, "GET", f"{_base(tenant, db)}/recoverable", params={"limit": limit})


@app.command()
@covers(
    "POST",
    "/api/catalog/undrop",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "schema": "--schema",
        "table": "--table",
        "asName": "--as-name",
        "fromSnapshot": "--from-snapshot",
    },
)
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


@app.command()
@covers(
    "POST",
    "/api/catalog/restore",
    {"tenant": "--tenant", "tenantDb": "--db", "schema": "--schema", "table": "--table", "to": "--to", "dryRun": "--dry-run"},
)
@covers(
    "POST",
    "/api/catalog/restore",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "schema": "--schema",
        "table": "--table",
        "to": "--to",
        "expectedCurrentSnapshot": "(computed from the dry-run response, gated by --yes)",
    },
)
def restore(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    db: str = typer.Option(..., "--db"),
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
    to: str = typer.Option(..., "--to", help="Target snapshot id or tag name."),
    dry_run: bool = typer.Option(False, "--dry-run", help="Preview the change summary; no write."),
    yes: bool = typer.Option(False, "--yes", help="Skip the interactive confirmation."),
):
    """Restore a live table to a prior snapshot (non-destructive: writes a new snapshot).

    Runs the dry run first and shows how many rows will be reverted; requires an ALL grant,
    or DDL plus RO/RW, on the table. For dropped tables use undrop instead.
    """
    body = {"tenant": tenant, "tenantDb": db, "schema": schema, "table": table, "to": to}
    preview = call(
        ctx,
        "POST",
        "/api/catalog/restore",
        body={**body, "dryRun": True},
        quiet=ctx.obj.json_output and not dry_run,
    )
    if dry_run:
        return
    if not yes and not typer.confirm(
        f"Restore {schema}.{table} to snapshot {preview['toSnapshot']}"
        f" (current {preview['currentSnapshot']})?"
    ):
        raise typer.Exit(1)
    call(
        ctx,
        "POST",
        "/api/catalog/restore",
        body={**body, "expectedCurrentSnapshot": preview["currentSnapshot"]},
    )
