import typer

from ._run import call

app = typer.Typer(help="Server-published configuration.")


@app.command()
def client(ctx: typer.Context):
    """Edge host/port/TLS for client bootstrapping (open endpoint)."""
    call(ctx, "GET", "/api/config/client")


@app.command()
def server(ctx: typer.Context):
    """Effective manager configuration."""
    call(ctx, "GET", "/api/config/server")
