import typer

from ..config import default_profile, list_profiles, set_default_profile
from ..output import render
from ..registry import covers
from ._run import call

app = typer.Typer(help="Server-published configuration and local CLI profiles.")


@app.command()
@covers("GET", "/api/config/client")
def client(ctx: typer.Context):
    """Edge host/port/TLS for client bootstrapping (open endpoint)."""
    call(ctx, "GET", "/api/config/client")


@app.command()
@covers("GET", "/api/config/server")
def server(ctx: typer.Context):
    """Effective manager configuration."""
    call(ctx, "GET", "/api/config/server")


@app.command()
def profiles(ctx: typer.Context):
    """List local CLI profiles; marks the one this invocation resolved to."""
    stored = list_profiles()
    names = sorted(set(stored) | {"default"})
    sticky = default_profile()
    rows = [
        {
            "profile": n,
            "active": n == ctx.obj.profile,
            "default": n == sticky,
            "manager_url": stored.get(n, {}).get("manager_url", "http://localhost:20900"),
            "tenant": stored.get(n, {}).get("tenant", ""),
        }
        for n in names
    ]
    render(rows, ctx.obj.json_output)


@app.command()
def use(ctx: typer.Context, name: str = typer.Argument(..., help="Profile name.")):
    """Set the sticky default profile (overridden per call by --profile / QOD_PROFILE)."""
    if name != "default" and name not in list_profiles():
        typer.echo(
            f"error: unknown profile '{name}' (create it with: qod --profile {name} auth login)",
            err=True,
        )
        raise typer.Exit(code=1)
    set_default_profile(name)
    typer.echo(f"default profile: {name}")
