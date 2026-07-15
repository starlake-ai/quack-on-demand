import typer

from ..rest import ApiError, RestClient
from ._run import call

app = typer.Typer(help="Topology manifest export/import (YAML).")


@app.command()
def export(
    ctx: typer.Context,
    out: str = typer.Option(None, "--out", help="File path; omit = stdout."),
):
    """Export the whole control-plane topology as YAML."""
    try:
        text = RestClient(ctx.obj.settings).request("GET", "/api/manifest/export", text=True)
    except ApiError as exc:
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
    if out is None:
        typer.echo(text, nl=False)
    else:
        with open(out, "w") as f:
            f.write(text)
        typer.echo(f"wrote {out}", err=True)


@app.command("import")
def import_(
    ctx: typer.Context,
    file: typer.FileText = typer.Argument(..., help="Manifest YAML file, or - for stdin."),
):
    """Import a topology manifest (YAML) into the control plane."""
    call(ctx, "POST", "/api/manifest/import", body=file.read())
