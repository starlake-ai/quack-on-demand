from urllib.parse import urlparse

import typer

from ..config import save_profile
from ..output import render
from ..registry import covers
from ..rest import ApiError, RestClient
from ._run import call

app = typer.Typer(help="Authentication: mode discovery and self-service password change.")


@app.command()
@covers("GET", "/api/auth/mode", {"tenant": "--tenant"})
def mode(ctx: typer.Context, tenant: str = typer.Option(None, "--tenant", help="Tenant to resolve auth mode for.")):
    """Show the auth mode (db or oidc) the manager expects."""
    call(ctx, "GET", "/api/auth/mode", params={"tenant": tenant})


@covers("POST", "/api/auth/login", {"username": "--username", "password": "(prompted)", "tenant": "--tenant"})
@covers("GET", "/api/config/client")
def login(
    ctx: typer.Context,
    url: str = typer.Option(None, "--url", help="Manager URL; defaults to the profile value."),
    username: str = typer.Option("admin", "--username"),
    tenant: str = typer.Option(
        None, "--tenant", help="Tenant for tenant-scoped logins (admins and regular users)."
    ),
    pool: str = typer.Option(None, "--pool", help="Default pool for qod sql; stored in the profile."),
):
    """Mint a session, store it and the edge settings in the active profile."""
    settings = ctx.obj.settings
    manager_url = url or settings.manager_url
    password = typer.prompt("Password", hide_input=True)
    settings.manager_url = manager_url
    client = RestClient(settings)
    body = {"username": username, "password": password}
    if tenant is not None:
        body["tenant"] = tenant
    try:
        login_resp = client.request("POST", "/api/auth/login", body=body)
        edge = client.request("GET", "/api/config/client")
    except ApiError as exc:
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
    edge_host = edge.get("flightSqlHost", "")
    if edge_host in ("", "0.0.0.0"):
        edge_host = urlparse(manager_url).hostname or "localhost"
    save_profile(
        ctx.obj.profile,
        {
            "manager_url": manager_url,
            "token": login_resp["token"],
            "sql_user": username,
            "tenant": tenant,
            "pool": pool,
            "edge_host": edge_host,
            "edge_port": edge.get("flightSqlPort", 31338),
            "edge_tls": edge.get("flightSqlTls", True),
        },
    )
    render(
        {"username": login_resp.get("username", username), "profile": ctx.obj.profile},
        ctx.obj.json_output,
    )


@app.command("change-password")
@covers(
    "POST",
    "/api/auth/change-password",
    {
        "tenant": "--tenant",
        "username": "--username",
        "currentPassword": "(prompted)",
        "newPassword": "(prompted)",
    },
)
def change_password(
    ctx: typer.Context,
    username: str = typer.Option(..., "--username"),
    tenant: str = typer.Option(None, "--tenant", help="Omit for a superuser account."),
):
    """Change your own password (works pre-login; the current password is the credential)."""
    current = typer.prompt("Current password", hide_input=True)
    new = typer.prompt("New password", hide_input=True, confirmation_prompt=True)
    call(
        ctx,
        "POST",
        "/api/auth/change-password",
        body={"tenant": tenant, "username": username, "currentPassword": current, "newPassword": new},
    )


@app.command("forgot-password")
@covers("POST", "/api/auth/forgot-password", {"tenant": "--tenant", "username": "--username"})
def forgot_password(
    ctx: typer.Context,
    username: str = typer.Option(..., "--username"),
    tenant: str = typer.Option(None, "--tenant", help="Omit for a superuser account."),
):
    """Request a password-reset link (always answers 200, account existence stays hidden)."""
    call(ctx, "POST", "/api/auth/forgot-password", body={"tenant": tenant, "username": username})


@app.command("reset-password")
@covers("POST", "/api/auth/reset-password", {"token": "(prompted)", "newPassword": "(prompted)"})
def reset_password(ctx: typer.Context):
    """Redeem a single-use reset link token and set a new password."""
    token = typer.prompt("Reset token")
    new = typer.prompt("New password", hide_input=True, confirmation_prompt=True)
    call(ctx, "POST", "/api/auth/reset-password", body={"token": token, "newPassword": new})


pat_app = typer.Typer(help="Personal access tokens for agents and scripts (MCP auth).")
app.add_typer(pat_app, name="pat")

VERB_CEILINGS = ("RO", "RW", "DDL", "ALL")


@pat_app.command()
@covers(
    "POST",
    "/api/auth/pat/create",
    {
        "name": "--name",
        "expiresAt": "--expires-at",
        "roles": "--role",
        "databases": "--database",
        "pools": "--pool",
        "tools": "--tool",
        "verbCeiling": "--verb-ceiling",
        "dropAdmin": "--drop-admin",
        "stmtTimeoutMs": "--stmt-timeout-ms",
        "maxRows": "--max-rows",
    },
)
def create(
    ctx: typer.Context,
    name: str = typer.Option(..., "--name", help="Label shown in listings."),
    expires_at: str = typer.Option(None, "--expires-at", help="Optional ISO-8601 expiry."),
    role: list[str] = typer.Option(
        None, "--role", help="Repeatable. Restrict the token to these roles; omit for unrestricted."
    ),
    database: list[str] = typer.Option(
        None,
        "--database",
        help="Repeatable. Restrict the token to these databases; omit for unrestricted.",
    ),
    pool: list[str] = typer.Option(
        None, "--pool", help="Repeatable. Restrict the token to these pools; omit for unrestricted."
    ),
    tool: list[str] = typer.Option(
        None, "--tool", help="Repeatable. Restrict the token to these tools; omit for unrestricted."
    ),
    verb_ceiling: str = typer.Option(
        None,
        "--verb-ceiling",
        help="Cap the token's write power: RO, RW, DDL or ALL (case-insensitive).",
    ),
    drop_admin: bool = typer.Option(
        False, "--drop-admin", help="Strip the owner's superuser/admin standing from this token."
    ),
    stmt_timeout_ms: int = typer.Option(
        None, "--stmt-timeout-ms", help="Per-statement timeout for this token, in milliseconds."
    ),
    max_rows: int = typer.Option(None, "--max-rows", help="Row cap for this token's queries."),
):
    """Create a PAT. The token is printed ONCE; store it now.

    Scope flags narrow the token below the owner's own grants; a flag left out means
    unrestricted on that axis, not empty.
    """
    body = {"name": name, "expiresAt": expires_at, "dropAdmin": drop_admin}
    if role:
        body["roles"] = list(role)
    if database:
        body["databases"] = list(database)
    if pool:
        body["pools"] = list(pool)
    if tool:
        body["tools"] = list(tool)
    if verb_ceiling is not None:
        upper = verb_ceiling.upper()
        if upper not in VERB_CEILINGS:
            raise typer.BadParameter(
                f"--verb-ceiling must be one of {', '.join(VERB_CEILINGS)} (got: {verb_ceiling!r})"
            )
        body["verbCeiling"] = upper
    if stmt_timeout_ms is not None:
        body["stmtTimeoutMs"] = stmt_timeout_ms
    if max_rows is not None:
        body["maxRows"] = max_rows
    call(ctx, "POST", "/api/auth/pat/create", body=body)


@pat_app.command("list")
@covers("POST", "/api/auth/pat/list")
def list_(ctx: typer.Context):
    """List your PATs (never shows token values)."""
    call(ctx, "POST", "/api/auth/pat/list")


@pat_app.command()
@covers("POST", "/api/auth/pat/revoke", {"id": "--id"})
def revoke(ctx: typer.Context, id: str = typer.Option(..., "--id")):
    """Revoke a PAT immediately."""
    call(ctx, "POST", "/api/auth/pat/revoke", body={"id": id})


@pat_app.command()
@covers("POST", "/api/auth/pat/delete", {"id": "--id"})
def delete(ctx: typer.Context, id: str = typer.Option(..., "--id")):
    """Delete a revoked or expired PAT from the listing (revoke live ones first)."""
    call(ctx, "POST", "/api/auth/pat/delete", body={"id": id})


@covers("POST", "/api/auth/logout")
def logout(ctx: typer.Context):
    """Revoke the current session token."""
    call(ctx, "POST", "/api/auth/logout")


@covers("GET", "/api/auth/whoami")
def whoami(ctx: typer.Context):
    """Verify the current session."""
    call(ctx, "GET", "/api/auth/whoami")
