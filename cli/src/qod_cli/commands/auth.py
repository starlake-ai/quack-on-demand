from urllib.parse import urlparse

import typer

from ..config import save_profile
from ..output import render
from ..rest import ApiError, RestClient
from ._run import call

app = typer.Typer(help="Authentication mode discovery.")


@app.command()
def mode(ctx: typer.Context):
    """Show the auth mode (db or oidc) the manager expects."""
    call(ctx, "GET", "/api/auth/mode")


def login(
    ctx: typer.Context,
    url: str = typer.Option(None, "--url", help="Manager URL; defaults to the profile value."),
    username: str = typer.Option("admin", "--username"),
    tenant: str = typer.Option(None, "--tenant", help="Tenant for tenant-scoped admins."),
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


def logout(ctx: typer.Context):
    """Revoke the current session token."""
    call(ctx, "POST", "/api/auth/logout")


def whoami(ctx: typer.Context):
    """Verify the current session."""
    call(ctx, "GET", "/api/auth/whoami")
