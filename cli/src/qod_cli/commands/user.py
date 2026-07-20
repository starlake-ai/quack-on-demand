import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="User principals.")


@app.command("list")
@covers("GET", "/api/user/list", {"tenant": "--tenant"})
def list_(ctx: typer.Context, tenant: str = typer.Option(None, "--tenant", help="Omit = all users incl. superusers.")):
    call(ctx, "GET", "/api/user/list", params={"tenant": tenant})


@app.command()
@covers(
    "POST",
    "/api/user/create",
    {
        "tenant": "--tenant",
        "username": "--username",
        "password": "--password",
        "role": "--role",
    },
)
def create(
    ctx: typer.Context,
    username: str = typer.Option(..., "--username"),
    tenant: str = typer.Option(None, "--tenant"),
    superuser: bool = typer.Option(False, "--superuser", help="Create a superuser (no tenant)."),
    password: str = typer.Option(None, "--password", help="Prompted when omitted."),
    role: str = typer.Option("user", "--role"),
):
    if superuser and tenant:
        raise typer.BadParameter("--superuser and --tenant are mutually exclusive")
    if not superuser and not tenant:
        raise typer.BadParameter("pass --tenant, or --superuser for a tenant-less superuser")
    if password is None:
        password = typer.prompt("Password", hide_input=True, confirmation_prompt=True)
    call(
        ctx,
        "POST",
        "/api/user/create",
        body={"tenant": None if superuser else tenant, "username": username, "password": password, "role": role},
    )


@app.command()
@covers(
    "POST",
    "/api/user/update",
    {"id": "ID", "tenant": "--tenant", "password": "--password", "role": "--role"},
)
def update(
    ctx: typer.Context,
    user_id: str = typer.Argument(..., metavar="ID"),
    tenant: str = typer.Option(None, "--tenant"),
    password: str = typer.Option(None, "--password", help="Omit = no rotation."),
    role: str = typer.Option(None, "--role"),
):
    body: dict = {"id": user_id}
    if tenant is not None:
        body["tenant"] = tenant
    if password is not None:
        body["password"] = password
    if role is not None:
        body["role"] = role
    call(ctx, "POST", "/api/user/update", body=body)


@app.command()
@covers("POST", "/api/user/delete", {"id": "ID"})
def delete(ctx: typer.Context, user_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/user/delete", body={"id": user_id})


@app.command()
@covers("GET", "/api/user/{id}/effective", {"id": "ID"})
def effective(ctx: typer.Context, user_id: str = typer.Argument(..., metavar="ID")):
    """Closure of roles, groups, table permissions, and pool grants."""
    call(ctx, "GET", f"/api/user/{user_id}/effective")
