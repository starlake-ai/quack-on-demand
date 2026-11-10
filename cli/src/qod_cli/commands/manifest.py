import typer

from ..registry import covers
from ..rest import ApiError, RestClient
from ._run import call

app = typer.Typer(help="Topology manifest export/import (YAML).")


@app.command()
@covers("GET", "/api/manifest/export", {"mode": "--mode", "tenant": "--tenant"})
def export(
    ctx: typer.Context,
    out: str = typer.Option(None, "--out", help="File path; omit = stdout."),
    mode: str = typer.Option(None, "--mode", help="rbac: only one tenant's roles and groups."),
    tenant: str = typer.Option(None, "--tenant", help="The tenant, with --mode rbac."),
):
    """Export the whole control-plane topology as YAML."""
    try:
        text = RestClient(ctx.obj.settings).request(
            "GET", "/api/manifest/export", params={"mode": mode, "tenant": tenant}, text=True
        )
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
@covers(
    "POST",
    "/api/manifest/import",
    {"body": "FILE", "mode": "--mode", "tenant": "--tenant"},
)
def import_(
    ctx: typer.Context,
    file: typer.FileText = typer.Argument(..., help="Manifest YAML file, or - for stdin."),
    mode: str = typer.Option(
        None,
        "--mode",
        help="rbac: make one tenant's custom roles and groups exactly this file.",
    ),
    tenant: str = typer.Option(None, "--tenant", help="The tenant, with --mode rbac."),
):
    """Import a topology manifest (YAML) into the control plane."""
    call(
        ctx,
        "POST",
        "/api/manifest/import",
        params={"mode": mode, "tenant": tenant},
        body=file.read(),
    )
