import typer

from ._run import call

app = typer.Typer(help="Groups.")


@app.command("list")
def list_(ctx: typer.Context, tenant: str = typer.Option(..., "--tenant")):
    call(ctx, "GET", "/api/group/list", params={"tenant": tenant})


@app.command()
def create(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    name: str = typer.Option(..., "--name"),
    description: str = typer.Option(None, "--description"),
):
    body: dict = {"tenant": tenant, "name": name}
    if description is not None:
        body["description"] = description
    call(ctx, "POST", "/api/group/create", body=body)


@app.command()
def delete(ctx: typer.Context, group_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/group/delete", body={"id": group_id})
