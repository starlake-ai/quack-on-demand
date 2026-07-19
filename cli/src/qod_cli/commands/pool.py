import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Pool lifecycle and pool-level access grants.")
permission_app = typer.Typer(help="Pool access grants for users and groups.")
app.add_typer(permission_app, name="permission")

TENANT = typer.Option(..., "--tenant")
DB = typer.Option(..., "--db", help="Tenant database name.")
POOL = typer.Option(..., "--pool")


def _key(tenant: str, db: str, pool: str) -> dict:
    return {"tenant": tenant, "tenantDb": db, "pool": pool}


@app.command("list")
@covers("GET", "/api/pool/list")
def list_(ctx: typer.Context):
    call(ctx, "GET", "/api/pool/list")


@app.command()
@covers(
    "POST",
    "/api/pool/create",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "pool": "--pool",
        "size": "--size",
        "roleDistribution": "--writeonly/--readonly/--dual",
        "idleTimeoutSec": "--idle-timeout-sec",
        "maxConcurrentPerNode": "--max-concurrent-per-node",
        "disabled": "--disabled",
        "cpu": "--cpu",
        "memory": "--memory",
        "podTemplateYaml": "--pod-template-file",
        "initSql": "--init-sql",
        "startSuspended": "--start-suspended",
        "cohorts": "--cohort",
    },
)
def create(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    pool: str = POOL,
    size: int = typer.Option(..., "--size"),
    writeonly: int = typer.Option(0, "--writeonly"),
    readonly: int = typer.Option(0, "--readonly"),
    dual: int = typer.Option(0, "--dual"),
    idle_timeout_sec: int = typer.Option(-1, "--idle-timeout-sec"),
    max_concurrent_per_node: int = typer.Option(0, "--max-concurrent-per-node"),
    disabled: bool = typer.Option(False, "--disabled"),
    init_sql: str = typer.Option(None, "--init-sql"),
    cpu: str = typer.Option("", "--cpu"),
    memory: str = typer.Option("", "--memory"),
    pod_template_file: typer.FileText = typer.Option(None, "--pod-template-file"),
    start_suspended: bool = typer.Option(False, "--start-suspended", help="Persist suspended; no nodes spawned until the first statement or `pool resume`."),
    cohort: list[str] = typer.Option(
        None,
        "--cohort",
        help="Repeatable placement cohort as writeonly,readonly,dual (e.g. 1,0,0). "
        "When supplied, cohort counts must sum to --size and roles must sum to "
        "--writeonly/--readonly/--dual.",
    ),
):
    body = {
        **_key(tenant, db, pool),
        "size": size,
        "roleDistribution": {"writeonly": writeonly, "readonly": readonly, "dual": dual},
        "idleTimeoutSec": idle_timeout_sec,
        "maxConcurrentPerNode": max_concurrent_per_node,
        "disabled": disabled,
        "cpu": cpu,
        "memory": memory,
        "podTemplateYaml": pod_template_file.read() if pod_template_file else "",
        "startSuspended": start_suspended,
    }
    if init_sql is not None:
        body["initSql"] = init_sql
    if cohort:
        cohorts = []
        for spec in cohort:
            parts = spec.split(",")
            if len(parts) != 3:
                raise typer.BadParameter(f"expected writeonly,readonly,dual, got {spec!r}")
            try:
                w, r, d = (int(p) for p in parts)
            except ValueError:
                raise typer.BadParameter(f"cohort must be three integers: writeonly,readonly,dual (got: {spec!r})")
            cohorts.append(
                {
                    "placement": {"nodeSelector": {}, "tolerations": []},
                    "distribution": {"writeonly": w, "readonly": r, "dual": d},
                }
            )
        body["cohorts"] = cohorts
    call(ctx, "POST", "/api/pool/create", body=body)


@app.command()
@covers(
    "POST",
    "/api/pool/scale",
    {
        "tenant": "--tenant",
        "tenantDb": "--db",
        "pool": "--pool",
        "targetSize": "--target-size",
        "roleDistribution": "--writeonly/--readonly/--dual",
        "force": "--force",
    },
)
def scale(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    pool: str = POOL,
    target_size: int = typer.Option(..., "--target-size"),
    writeonly: int = typer.Option(0, "--writeonly"),
    readonly: int = typer.Option(0, "--readonly"),
    dual: int = typer.Option(0, "--dual"),
    force: bool = typer.Option(False, "--force"),
):
    call(
        ctx,
        "POST",
        "/api/pool/scale",
        body={
            **_key(tenant, db, pool),
            "targetSize": target_size,
            "roleDistribution": {"writeonly": writeonly, "readonly": readonly, "dual": dual},
            "force": force,
        },
    )


@app.command()
@covers("POST", "/api/pool/stop", {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "force": "--force"})
def stop(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, force: bool = typer.Option(False, "--force")):
    call(ctx, "POST", "/api/pool/stop", body={**_key(tenant, db, pool), "force": force})


@app.command()
@covers("POST", "/api/pool/delete", {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "force": "--force"})
def delete(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, force: bool = typer.Option(False, "--force")):
    call(ctx, "POST", "/api/pool/delete", body={**_key(tenant, db, pool), "force": force})


@app.command("set-disabled")
@covers(
    "POST",
    "/api/pool/setDisabled",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "disabled": "--disabled/--enabled"},
)
def set_disabled(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, disabled: bool = typer.Option(..., "--disabled/--enabled")):
    call(ctx, "POST", "/api/pool/setDisabled", body={**_key(tenant, db, pool), "disabled": disabled})


@app.command("set-resources")
@covers(
    "POST",
    "/api/pool/setResources",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "cpu": "--cpu", "memory": "--memory"},
)
def set_resources(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, cpu: str = typer.Option(..., "--cpu"), memory: str = typer.Option(..., "--memory")):
    call(ctx, "POST", "/api/pool/setResources", body={**_key(tenant, db, pool), "cpu": cpu, "memory": memory})


@app.command("set-pod-template")
@covers(
    "POST",
    "/api/pool/setPodTemplate",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool", "podTemplateYaml": "--file"},
)
def set_pod_template(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL, file: typer.FileText = typer.Option(..., "--file")):
    call(ctx, "POST", "/api/pool/setPodTemplate", body={**_key(tenant, db, pool), "podTemplateYaml": file.read()})


@app.command()
@covers("POST", "/api/pool/suspend", {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool"})
def suspend(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL):
    """Scale the pool to zero, keeping its distribution; wakes on the next query."""
    call(ctx, "POST", "/api/pool/suspend", body=_key(tenant, db, pool))


@app.command()
@covers("POST", "/api/pool/resume", {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool"})
def resume(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL):
    """Wake a suspended pool (respawn to its stored distribution)."""
    call(ctx, "POST", "/api/pool/resume", body=_key(tenant, db, pool))


@app.command()
@covers(
    "GET",
    "/api/pool/{tenant}/{tenantDb}/{pool}/status",
    {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool"},
)
def status(ctx: typer.Context, tenant: str = TENANT, db: str = DB, pool: str = POOL):
    """Fetch a single pool's live status (nodes, suspended/disabled flags, resources)."""
    call(ctx, "GET", f"/api/pool/{tenant}/{db}/{pool}/status")


@permission_app.command("list")
@covers(
    "GET",
    "/api/pool/permission/list",
    {"tenant": "--tenant", "userId": "--user-id", "groupId": "--group-id"},
)
def permission_list(
    ctx: typer.Context,
    tenant: str = typer.Option(None, "--tenant"),
    user_id: str = typer.Option(None, "--user-id"),
    group_id: str = typer.Option(None, "--group-id"),
):
    call(ctx, "GET", "/api/pool/permission/list", params={"tenant": tenant, "userId": user_id, "groupId": group_id})


@permission_app.command("grant")
@covers(
    "POST",
    "/api/pool/permission/grant",
    {"tenant": "--tenant", "poolId": "--pool-id", "userId": "--user-id", "groupId": "--group-id"},
)
def permission_grant(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    pool_id: str = typer.Option(None, "--pool-id", help="Omit = every pool in the tenant."),
    user_id: str = typer.Option(None, "--user-id"),
    group_id: str = typer.Option(None, "--group-id", help="Exactly one of --user-id / --group-id."),
):
    body: dict = {"tenant": tenant}
    if pool_id is not None:
        body["poolId"] = pool_id
    if user_id is not None:
        body["userId"] = user_id
    if group_id is not None:
        body["groupId"] = group_id
    call(ctx, "POST", "/api/pool/permission/grant", body=body)


@permission_app.command("revoke")
@covers("POST", "/api/pool/permission/revoke", {"id": "ID"})
def permission_revoke(ctx: typer.Context, grant_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/pool/permission/revoke", body={"id": grant_id})
