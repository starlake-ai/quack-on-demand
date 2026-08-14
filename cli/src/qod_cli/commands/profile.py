import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Your own profile: usage and recent statements (any session).")


@app.command()
@covers("GET", "/api/profile/usage", {"days": "--days"})
def usage(ctx: typer.Context, days: int = typer.Option(None, "--days", help="Window in days (default 30, max 365).")):
    """Your own daily statement counts."""
    call(ctx, "GET", "/api/profile/usage", params={"days": days})


@app.command()
@covers("GET", "/api/profile/statements", {"limit": "--limit"})
def statements(ctx: typer.Context, limit: int = typer.Option(None, "--limit", help="Max rows (default 50).")):
    """Your own most recent statements."""
    call(ctx, "GET", "/api/profile/statements", params={"limit": limit})
