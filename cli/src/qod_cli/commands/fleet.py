import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Fleet servers (runtimeType=fleet): list, drain, undrain, remove.")


@app.command()
@covers("GET", "/api/fleet/servers")
def servers(ctx: typer.Context):
    """List joined servers with liveness, capacity and assignment."""
    call(ctx, "GET", "/api/fleet/servers")


@app.command()
@covers("POST", "/api/fleet/server/drain", {"name": "NAME"})
def drain(ctx: typer.Context, name: str = typer.Argument(..., help="Server name.")):
    """Release the server's node and stop scheduling onto it."""
    call(ctx, "POST", "/api/fleet/server/drain", body={"name": name})


@app.command()
@covers("POST", "/api/fleet/server/undrain", {"name": "NAME"})
def undrain(ctx: typer.Context, name: str = typer.Argument(...)):
    """Make a drained server schedulable again."""
    call(ctx, "POST", "/api/fleet/server/undrain", body={"name": name})


@app.command()
@covers("POST", "/api/fleet/server/remove", {"name": "NAME"})
def remove(ctx: typer.Context, name: str = typer.Argument(...)):
    """Forget a drained or unreachable server (stop its agent first)."""
    call(ctx, "POST", "/api/fleet/server/remove", body={"name": name})
