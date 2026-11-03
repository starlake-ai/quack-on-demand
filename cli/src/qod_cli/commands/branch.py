"""Branches (Epic 1): create, list, show, changes, diff, propose, merge, discard."""

import typer

from ..registry import covers
from ..rest import UNTIL_ANSWERED
from ._run import call

app = typer.Typer(
    help=(
        "Writable branches of a DuckLake database: agents write on a branch, a human reviews "
        "the change set and merges."
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
    """The branch's change set since its fork and its table conflicts against main."""
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


def _parse_keys(values: list[str] | None) -> dict[str, list[str]]:
    keys: dict[str, list[str]] = {}
    for value in values or []:
        table, sep, columns = value.partition("=")
        names = [c.strip() for c in columns.split(",") if c.strip()]
        if not sep or "." not in table or not names:
            raise typer.BadParameter(
                f"{value!r} must read schema.table=column[,column...]", param_hint="--key"
            )
        table = table.strip()
        if table in keys:
            raise typer.BadParameter(
                f"{table!r} is named twice; give all its columns in one --key, comma separated",
                param_hint="--key",
            )
        keys[table] = names
    return keys


@app.command()
@covers(
    "POST",
    "/api/branch/propose",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "branch": "--branch",
        "onConflict": "--on-conflict",
        "keys": "--key",
    },
)
def propose(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    branch: str = BRANCH,
    on_conflict: str = typer.Option(
        None,
        "--on-conflict",
        help="Which side wins every row conflict (a cell, a row one side deleted and the other updated, rows matching a --key): main or branch. Without it, any conflict refuses the merge.",
    ),
    key: list[str] = typer.Option(
        None,
        "--key",
        help="schema.table=column[,column...]: rows both sides inserted with this key become conflicts. Repeatable.",
    ),
):
    """Propose the branch for merge (records the change set and what a merge would do to its rows)."""
    body = {"tenant": tenant, "tenantDb": db, "branch": branch}
    if on_conflict is not None:
        body["onConflict"] = on_conflict
    keys = _parse_keys(key)
    if keys:
        body["keys"] = keys
    call(ctx, "POST", "/api/branch/propose", body=body, timeout=UNTIL_ANSWERED)


@app.command()
@covers(
    "POST",
    "/api/branch/merge",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "branch": "--branch",
        "proposalId": "--proposal",
        "expectedMainSnapshot": "--expect-main",
        "holdWriters": "--hold-writers",
    },
)
def merge(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    branch: str = BRANCH,
    proposal: str = typer.Option(
        ...,
        "--proposal",
        help="The id of the open proposal you read: merge.id in the reply of 'branch propose', or the id of the entry with status proposed under merges in 'branch show'. Another one in its place is refused (409).",
    ),
    expect_main: int = typer.Option(
        None, "--expect-main", help="Refuse when main is no longer at this snapshot (409)."
    ),
    hold_writers: bool = typer.Option(
        False,
        "--hold-writers",
        help="Refuse other commits on the merged tables until the merge ends. They retry for about 9 seconds.",
    ),
):
    """Merge a proposed branch onto main (approver must differ from the proposer)."""
    body = {"tenant": tenant, "tenantDb": db, "branch": branch, "proposalId": proposal}
    if expect_main is not None:
        body["expectedMainSnapshot"] = expect_main
    if hold_writers:
        body["holdWriters"] = True
    call(ctx, "POST", "/api/branch/merge", body=body, timeout=UNTIL_ANSWERED)


@app.command()
@covers("POST", "/api/branch/discard", {"tenant": "--tenant", "tenantDb": "--db", "branch": "--branch"})
def discard(ctx: typer.Context, tenant: str = TENANT, db: str = DB, branch: str = BRANCH):
    """Discard a live branch: its pool, catalog and branch-only files are freed."""
    call(ctx, "POST", "/api/branch/discard", body={"tenant": tenant, "tenantDb": db, "branch": branch})
