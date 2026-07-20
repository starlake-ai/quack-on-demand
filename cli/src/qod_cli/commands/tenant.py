import typer

from ..registry import covers
from ._run import call, kv_pairs

app = typer.Typer(help="Tenant CRUD.")


@app.command("list")
@covers("GET", "/api/tenant/list")
def list_(ctx: typer.Context):
    call(ctx, "GET", "/api/tenant/list")


@app.command()
@covers(
    "POST",
    "/api/tenant/create",
    {
        "id": "ID",
        "displayName": "--display-name",
        "authProvider": "--auth-provider",
        "authConfig": "--auth-config",
    },
)
def create(
    ctx: typer.Context,
    tenant_id: str = typer.Argument(..., metavar="ID", help="Lowercase slug, e.g. acme."),
    display_name: str = typer.Option("", "--display-name"),
    auth_provider: str = typer.Option("db", "--auth-provider", help="db|keycloak|google|azure|aws"),
    auth_config: list[str] = typer.Option(None, "--auth-config", help="KEY=VALUE, repeatable."),
):
    call(
        ctx,
        "POST",
        "/api/tenant/create",
        body={
            "id": tenant_id,
            "displayName": display_name,
            "authProvider": auth_provider,
            "authConfig": kv_pairs(auth_config),
        },
    )


@app.command()
@covers("POST", "/api/tenant/delete", {"name": "NAME"})
def delete(ctx: typer.Context, name: str = typer.Argument(...)):
    """Delete a tenant (must have no pools)."""
    call(ctx, "POST", "/api/tenant/delete", body={"name": name})


@app.command("set-disabled")
@covers("POST", "/api/tenant/setDisabled", {"name": "NAME", "disabled": "--disabled/--enabled"})
def set_disabled(
    ctx: typer.Context,
    name: str = typer.Argument(...),
    disabled: bool = typer.Option(..., "--disabled/--enabled"),
):
    call(ctx, "POST", "/api/tenant/setDisabled", body={"name": name, "disabled": disabled})


@app.command("set-auth")
@covers(
    "POST",
    "/api/tenant/setAuth",
    {"name": "NAME", "authProvider": "--auth-provider", "authConfig": "--auth-config"},
)
def set_auth(
    ctx: typer.Context,
    name: str = typer.Argument(...),
    auth_provider: str = typer.Option(..., "--auth-provider"),
    auth_config: list[str] = typer.Option(None, "--auth-config", help="KEY=VALUE, repeatable."),
):
    call(
        ctx,
        "POST",
        "/api/tenant/setAuth",
        body={"name": name, "authProvider": auth_provider, "authConfig": kv_pairs(auth_config)},
    )
