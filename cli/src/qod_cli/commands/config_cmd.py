import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Server-published configuration.")


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
