import typer

from ._run import call

app = typer.Typer(help="Managed maintenance policies and runs.")

TENANT = typer.Option(..., "--tenant")
DB = typer.Option(..., "--db")


@app.command()
def policy(ctx: typer.Context, tenant: str = TENANT, db: str = DB):
    call(ctx, "GET", "/api/maintenance/policy", params={"tenant": tenant, "tenantDb": db})


@app.command("policy-upsert")
def policy_upsert(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    scope_kind: str = typer.Option(..., "--scope-kind", help="tenantdb|schema|table"),
    scope_schema: str = typer.Option(None, "--scope-schema"),
    scope_table: str = typer.Option(None, "--scope-table"),
    enabled: bool = typer.Option(None, "--enabled/--disabled"),
    retention_days: int = typer.Option(None, "--retention-days"),
    compaction_enabled: bool = typer.Option(None, "--compaction-enabled/--compaction-disabled"),
    target_file_size: str = typer.Option(None, "--target-file-size"),
    small_file_min_count: int = typer.Option(None, "--small-file-min-count"),
    rewrite_delete_threshold: float = typer.Option(None, "--rewrite-delete-threshold"),
    cleanup_grace_days: int = typer.Option(None, "--cleanup-grace-days"),
    orphan_min_age_days: int = typer.Option(None, "--orphan-min-age-days"),
    cron: str = typer.Option(None, "--cron"),
):
    body: dict = {"tenant": tenant, "tenantDb": db, "scopeKind": scope_kind}
    optional = {
        "scopeSchema": scope_schema,
        "scopeTable": scope_table,
        "enabled": enabled,
        "retentionDays": retention_days,
        "compactionEnabled": compaction_enabled,
        "targetFileSize": target_file_size,
        "smallFileMinCount": small_file_min_count,
        "rewriteDeleteThreshold": rewrite_delete_threshold,
        "cleanupGraceDays": cleanup_grace_days,
        "orphanMinAgeDays": orphan_min_age_days,
        "cron": cron,
    }
    body.update({k: v for k, v in optional.items() if v is not None})
    call(ctx, "POST", "/api/maintenance/policy/upsert", body=body)


@app.command("policy-delete")
def policy_delete(ctx: typer.Context, policy_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/maintenance/policy/delete", body={"id": policy_id})


@app.command()
def run(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    scope: str = typer.Option(None, "--scope"),
    operations: str = typer.Option(None, "--operations", help="Comma-separated."),
):
    body: dict = {"tenant": tenant, "tenantDb": db}
    if scope is not None:
        body["scope"] = scope
    if operations is not None:
        body["operations"] = operations
    call(ctx, "POST", "/api/maintenance/run", body=body)


@app.command()
def runs(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    limit: int = typer.Option(None, "--limit"),
    before: int = typer.Option(None, "--before"),
):
    call(ctx, "GET", "/api/maintenance/runs", params={"tenant": tenant, "tenantDb": db, "limit": limit, "before": before})
