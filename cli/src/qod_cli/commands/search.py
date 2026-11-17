"""QoD Search: the full-text index registry of DuckLake tables (create, list, drop, build)."""

import typer

from ..registry import covers
from ..rest import UNTIL_ANSWERED
from ._run import call

app = typer.Typer(
    help=(
        "Full-text search indexes of DuckLake tables. An index is keyed by the table's DuckLake id, "
        "so a rename keeps it and a drop disables it. k1 and b are fixed at 1.2 and 0.75."
    )
)

TENANT = typer.Option(..., "--tenant", help="Tenant id.")
DATABASE = typer.Option(..., "--database", help="Database (tenant-db) name.")
TABLE = typer.Option(
    ...,
    "--table",
    help="TABLE or SCHEMA.TABLE; the schema defaults to the database's.",
)


@app.command("create-index")
@covers(
    "POST",
    "/api/search/index/create",
    {"tenant": "--tenant", "database": "--database", "table": "--table", "columns": "--column"},
)
def create_index(
    ctx: typer.Context,
    tenant: str = TENANT,
    database: str = DATABASE,
    table: str = TABLE,
    column: list[str] = typer.Option(
        ...,
        "--column",
        help="A VARCHAR column to index; repeat for each (1 to 32), in order.",
    ),
):
    """Create a search index on a table's VARCHAR columns."""
    call(
        ctx,
        "POST",
        "/api/search/index/create",
        body={"tenant": tenant, "database": database, "table": table, "columns": list(column)},
    )


@app.command("list-indexes")
@covers("POST", "/api/search/index/list", {"tenant": "--tenant", "database": "--database"})
def list_indexes(
    ctx: typer.Context,
    tenant: str = TENANT,
    database: str = typer.Option(
        None, "--database", help="Only this database's indexes; default every database."
    ),
):
    """List a tenant's search indexes (an index whose table was dropped reads disabled)."""
    body = {"tenant": tenant}
    if database is not None:
        body["database"] = database
    call(ctx, "POST", "/api/search/index/list", body=body)


@app.command("drop-index")
@covers(
    "POST",
    "/api/search/index/delete",
    {"tenant": "--tenant", "database": "--database", "table": "--table", "indexId": "--index-id"},
)
def drop_index(
    ctx: typer.Context,
    tenant: str = TENANT,
    database: str = DATABASE,
    table: str = typer.Option(
        None,
        "--table",
        help="TABLE or SCHEMA.TABLE, the table's current name.",
    ),
    index_id: str = typer.Option(
        None,
        "--index-id",
        help="The index's id (from `qod search list-indexes`); reaches a disabled index whose "
        "name a recreated table now holds.",
    ),
):
    """Drop a search index, named by its table or by its id (exactly one)."""
    if (table is None) == (index_id is None):
        raise typer.BadParameter("give exactly one of --table and --index-id")
    body = {"tenant": tenant, "database": database}
    if table is not None:
        body["table"] = table
    else:
        body["indexId"] = index_id
    call(ctx, "POST", "/api/search/index/delete", body=body)


@app.command("build")
@covers(
    "POST",
    "/api/search/index/build",
    {"tenant": "--tenant", "database": "--database", "table": "--table", "pool": "--pool"},
)
def build(
    ctx: typer.Context,
    tenant: str = TENANT,
    database: str = DATABASE,
    table: str = TABLE,
    pool: str = typer.Option(..., "--pool", help="The pool whose node builds the bundles."),
):
    """Build the bundles of a table's search index (superuser; interim until the indexer)."""
    call(
        ctx,
        "POST",
        "/api/search/index/build",
        body={"tenant": tenant, "database": database, "table": table, "pool": pool},
        # One statement per data file, each bounded by the server: no read limit here.
        timeout=UNTIL_ANSWERED,
    )
