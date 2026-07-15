import typer

from ._run import call

app = typer.Typer(help="RBAC membership edges.")
user_role_app = typer.Typer(help="User to role memberships.")
user_group_app = typer.Typer(help="User to group memberships.")
group_role_app = typer.Typer(help="Group to role memberships.")
app.add_typer(user_role_app, name="user-role")
app.add_typer(user_group_app, name="user-group")
app.add_typer(group_role_app, name="group-role")

USER = typer.Option(..., "--user-id")
ROLE = typer.Option(..., "--role-id")
GROUP = typer.Option(..., "--group-id")


@user_role_app.command()
def add(ctx: typer.Context, user_id: str = USER, role_id: str = ROLE):
    call(ctx, "POST", "/api/membership/user-role/add", body={"userId": user_id, "roleId": role_id})


@user_role_app.command()
def remove(ctx: typer.Context, user_id: str = USER, role_id: str = ROLE):
    call(ctx, "POST", "/api/membership/user-role/remove", body={"userId": user_id, "roleId": role_id})


@user_group_app.command("add")
def ug_add(ctx: typer.Context, user_id: str = USER, group_id: str = GROUP):
    call(ctx, "POST", "/api/membership/user-group/add", body={"userId": user_id, "groupId": group_id})


@user_group_app.command("remove")
def ug_remove(ctx: typer.Context, user_id: str = USER, group_id: str = GROUP):
    call(ctx, "POST", "/api/membership/user-group/remove", body={"userId": user_id, "groupId": group_id})


@group_role_app.command("add")
def gr_add(ctx: typer.Context, group_id: str = GROUP, role_id: str = ROLE):
    call(ctx, "POST", "/api/membership/group-role/add", body={"groupId": group_id, "roleId": role_id})


@group_role_app.command("remove")
def gr_remove(ctx: typer.Context, group_id: str = GROUP, role_id: str = ROLE):
    call(ctx, "POST", "/api/membership/group-role/remove", body={"groupId": group_id, "roleId": role_id})


@group_role_app.command("list")
def gr_list(ctx: typer.Context, group_id: str = GROUP):
    call(ctx, "GET", "/api/membership/group-role/list", params={"groupId": group_id})
