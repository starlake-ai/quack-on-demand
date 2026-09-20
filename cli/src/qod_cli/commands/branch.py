"""Branches (Epic 1): create, list, show, changes, diff, propose, merge, discard."""

import typer

from ..registry import covers
from ._run import call

app = typer.Typer(
    help=(
        "Writable branches of a DuckLake database: agents write on a branch, a human reviews "
        "the change set and fast-forward merges."
    )
)

TENANT = typer.Option(..., "--tenant")
DB = typer.Option(..., "--db", help="Parent database (tenant-db) name.")
BRANCH = typer.Option(..., "--branch", help="Branch name.")


def _base(tenant: str, db: str) -> str:
    return f"/api/branch/tenant/{tenant}/database/{db}/branches"


@app.command()
@covers(
    "POST",
    "/api/branch/create",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "name": "--name",
        "ttlHours": "--ttl-hours",
        "fromSnapshot": "--from-snapshot",
    },
)
def create(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    name: str = typer.Option(..., "--name", help="Branch name (lowercase, letters/digits/_/-)."),
    ttl_hours: int = typer.Option(
        None, "--ttl-hours", help="Hours until the branch expires; 0 = never; default = server."
    ),
    from_snapshot: int = typer.Option(
        None,
        "--from-snapshot",
        help="Reserved: v1 forks at the parent head and refuses any other snapshot.",
    ),
):
    """Create a branch: a zero-copy clone of the catalog at its current head, with its own pool.

    Target it with `qod sql --branch NAME` (FlightSQL `branch` header) or the MCP `branch`
    argument; the live database is never touched.
    """
    body = {"tenant": tenant, "tenantDb": db, "name": name}
    if ttl_hours is not None:
        body["ttlHours"] = ttl_hours
    if from_snapshot is not None:
        body["fromSnapshot"] = from_snapshot
    call(ctx, "POST", "/api/branch/create", body=body)


@app.command("list")
@covers(
    "GET",
    "/api/branch/tenant/{tenant}/database/{tenantDb}/branches",
    {"tenant": "--tenant", "tenantDb": "--db", "includeTerminal": "--all"},
)
def list_branches(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    all_: bool = typer.Option(False, "--all", help="Include merged, discarded and expired branches."),
):
    """List the live branches of a database."""
    call(ctx, "GET", _base(tenant, db), params={"includeTerminal": all_ or None})


@app.command()
@covers(
    "GET",
    "/api/branch/tenant/{tenant}/database/{tenantDb}/branches/{branch}",
    {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch"},
)
def show(ctx: typer.Context, tenant: str = TENANT, db: str = DB, branch: str = BRANCH):
    """One branch with its merge history."""
    call(ctx, "GET", f"{_base(tenant, db)}/{branch}")


@app.command()
@covers(
    "GET",
    "/api/branch/tenant/{tenant}/database/{tenantDb}/branches/{branch}/changes",
    {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch", "counts": "--no-counts"},
)
def changes(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    branch: str = BRANCH,
    no_counts: bool = typer.Option(False, "--no-counts", help="Skip per-table row counts."),
):
    """The branch's change set since its fork, its conflicts against main, and the merge verdict."""
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/{branch}/changes",
        params={"counts": False if no_counts else None},
    )


@app.command()
@covers(
    "GET",
    "/api/branch/tenant/{tenant}/database/{tenantDb}/branches/{branch}/diff",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "branch": "--branch",
        "schema": "--schema",
        "table": "--table",
        "limit": "--limit",
        "cursor": "--cursor",
        "changeType": "--change-type",
    },
)
def diff(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    branch: str = BRANCH,
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
    limit: int = typer.Option(None, "--limit"),
    cursor: str = typer.Option(None, "--cursor"),
    change_type: str = typer.Option(None, "--change-type", help="insert, delete, update (comma-separated)."),
):
    """Row-level diff of one table between the branch's fork and its head."""
    call(
        ctx,
        "GET",
        f"{_base(tenant, db)}/{branch}/diff",
        params={
            "schema": schema,
            "table": table,
            "limit": limit,
            "cursor": cursor,
            "changeType": change_type,
        },
    )


@app.command("schema-diff")
@covers(
    "GET",
    "/api/branch/tenant/{tenant}/database/{tenantDb}/branches/{branch}/schema-diff",
    {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch", "schema": "--schema", "table": "--table"},
)
def schema_diff(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    branch: str = BRANCH,
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
):
    """Column-level diff of one table between the branch's fork and its head."""
    call(ctx, "GET", f"{_base(tenant, db)}/{branch}/schema-diff", params={"schema": schema, "table": table})


@app.command()
@covers("POST", "/api/branch/propose", {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch"})
def propose(ctx: typer.Context, tenant: str = TENANT, db: str = DB, branch: str = BRANCH):
    """Propose the branch for merge (records the change set and conflicts as of now)."""
    call(ctx, "POST", "/api/branch/propose", body={"tenant": tenant, "tenantDb": db, "branch": branch})


@app.command()
@covers(
    "POST",
    "/api/branch/merge",
    {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch", "expectedMainSnapshot": "--expect-main"},
)
def merge(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    branch: str = BRANCH,
    expect_main: int = typer.Option(
        None, "--expect-main", help="Refuse when main is no longer at this snapshot (409)."
    ),
):
    """Fast-forward merge a proposed branch onto main (approver must differ from the proposer)."""
    body = {"tenant": tenant, "tenantDb": db, "branch": branch}
    if expect_main is not None:
        body["expectedMainSnapshot"] = expect_main
    call(ctx, "POST", "/api/branch/merge", body=body)


@app.command()
@covers("POST", "/api/branch/discard", {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch"})
def discard(ctx: typer.Context, tenant: str = TENANT, db: str = DB, branch: str = BRANCH):
    """Discard a live branch: its pool, catalog and branch-only files are freed."""
    call(ctx, "POST", "/api/branch/discard", body={"tenant": tenant, "tenantDb": db, "branch": branch})
