import typer

from ._run import call

audit_app = typer.Typer(help="Audit log.")
history_app = typer.Typer(help="Statement history and trends.")


@audit_app.command("list")
def audit_list(
    ctx: typer.Context,
    family: str = typer.Option(None, "--family"),
    tenant: str = typer.Option(None, "--tenant"),
    actor: str = typer.Option(None, "--actor"),
    action: str = typer.Option(None, "--action"),
    q: str = typer.Option(None, "--q", help="Free-text filter."),
    from_: str = typer.Option(None, "--from"),
    to: str = typer.Option(None, "--to"),
    limit: int = typer.Option(None, "--limit"),
    before: str = typer.Option(None, "--before", help="Keyset pagination token."),
    no_tenant: bool = typer.Option(None, "--no-tenant", help="Only rows without a tenant."),
):
    call(
        ctx,
        "GET",
        "/api/audit/list",
        params={
            "family": family, "tenant": tenant, "actor": actor, "action": action, "q": q,
            "from": from_, "to": to, "limit": limit, "before": before, "noTenant": no_tenant,
        },
    )


@audit_app.command()
def actions(ctx: typer.Context):
    """Distinct audit action names, for filter values."""
    call(ctx, "GET", "/api/audit/actions")


def usage(
    ctx: typer.Context,
    granularity: str = typer.Option(None, "--granularity"),
    from_: str = typer.Option(None, "--from"),
    to: str = typer.Option(None, "--to"),
    group_by: str = typer.Option(None, "--group-by"),
    tenant: str = typer.Option(None, "--tenant"),
    pool: str = typer.Option(None, "--pool"),
):
    """Usage accounting rollups."""
    call(
        ctx,
        "GET",
        "/api/usage",
        params={"granularity": granularity, "from": from_, "to": to, "groupBy": group_by, "tenant": tenant, "pool": pool},
    )


@history_app.command()
def statements(
    ctx: typer.Context,
    from_: str = typer.Option(None, "--from"),
    to: str = typer.Option(None, "--to"),
    tenant: str = typer.Option(None, "--tenant"),
    pool: str = typer.Option(None, "--pool"),
    user: str = typer.Option(None, "--user"),
    status: str = typer.Option(None, "--status"),
    q: str = typer.Option(None, "--q"),
    limit: int = typer.Option(None, "--limit"),
    before: str = typer.Option(None, "--before"),
):
    call(
        ctx,
        "GET",
        "/api/history/statements",
        params={
            "from": from_, "to": to, "tenant": tenant, "pool": pool, "user": user,
            "status": status, "q": q, "limit": limit, "before": before,
        },
    )


@history_app.command()
def trends(
    ctx: typer.Context,
    granularity: str = typer.Option(None, "--granularity"),
    from_: str = typer.Option(None, "--from"),
    to: str = typer.Option(None, "--to"),
    tenant: str = typer.Option(None, "--tenant"),
    pool: str = typer.Option(None, "--pool"),
):
    call(
        ctx,
        "GET",
        "/api/history/trends",
        params={"granularity": granularity, "from": from_, "to": to, "tenant": tenant, "pool": pool},
    )
