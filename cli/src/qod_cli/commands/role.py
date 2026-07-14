import typer

from ._run import call

app = typer.Typer(help="Roles, table permissions, and row/column policies.")
permission_app = typer.Typer(help="Table permissions attached to a role.")
row_policy_app = typer.Typer(help="Row-level security predicates.")
column_policy_app = typer.Typer(help="Column-level security policies.")
app.add_typer(permission_app, name="permission")
app.add_typer(row_policy_app, name="row-policy")
app.add_typer(column_policy_app, name="column-policy")


@app.command("list")
def list_(ctx: typer.Context, tenant: str = typer.Option(..., "--tenant")):
    call(ctx, "GET", "/api/role/list", params={"tenant": tenant})


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
    call(ctx, "POST", "/api/role/create", body=body)


@app.command()
def delete(ctx: typer.Context, role_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/role/delete", body={"id": role_id})


@permission_app.command("list")
def permission_list(ctx: typer.Context, role_id: str = typer.Option(..., "--role-id")):
    call(ctx, "GET", "/api/role/permission/list", params={"roleId": role_id})


@permission_app.command("grant")
def permission_grant(
    ctx: typer.Context,
    role_id: str = typer.Option(..., "--role-id"),
    verb: str = typer.Option(..., "--verb", help="SELECT|INSERT|UPDATE|DELETE|ALL"),
    catalog: str = typer.Option("*", "--catalog"),
    schema: str = typer.Option("*", "--schema"),
    table: str = typer.Option("*", "--table"),
):
    call(
        ctx,
        "POST",
        "/api/role/permission/grant",
        body={"roleId": role_id, "catalog": catalog, "schema": schema, "table": table, "verb": verb},
    )


@permission_app.command("revoke")
def permission_revoke(ctx: typer.Context, permission_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/role/permission/revoke", body={"id": permission_id})


@row_policy_app.command("list")
def row_policy_list(ctx: typer.Context, role_id: str = typer.Option(..., "--role-id")):
    call(ctx, "GET", "/api/role/row-policy/list", params={"roleId": role_id})


@row_policy_app.command("create")
def row_policy_create(
    ctx: typer.Context,
    role_id: str = typer.Option(..., "--role-id"),
    catalog: str = typer.Option(..., "--catalog"),
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
    predicate_sql: str = typer.Option(..., "--predicate-sql"),
):
    call(
        ctx,
        "POST",
        "/api/role/row-policy/create",
        body={
            "roleId": role_id,
            "catalogName": catalog,
            "schemaName": schema,
            "tableName": table,
            "predicateSql": predicate_sql,
        },
    )


@row_policy_app.command("update")
def row_policy_update(
    ctx: typer.Context,
    policy_id: str = typer.Argument(..., metavar="ID"),
    predicate_sql: str = typer.Option(..., "--predicate-sql"),
):
    call(ctx, "POST", "/api/role/row-policy/update", body={"id": policy_id, "predicateSql": predicate_sql})


@row_policy_app.command("delete")
def row_policy_delete(ctx: typer.Context, policy_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/role/row-policy/delete", body={"id": policy_id})


@column_policy_app.command("list")
def column_policy_list(ctx: typer.Context, role_id: str = typer.Option(..., "--role-id")):
    call(ctx, "GET", "/api/role/column-policy/list", params={"roleId": role_id})


@column_policy_app.command("create")
def column_policy_create(
    ctx: typer.Context,
    role_id: str = typer.Option(..., "--role-id"),
    catalog: str = typer.Option(..., "--catalog"),
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
    column: str = typer.Option(..., "--column"),
    action: str = typer.Option(..., "--action"),
    transform_sql: str = typer.Option(None, "--transform-sql"),
):
    body: dict = {
        "roleId": role_id,
        "catalogName": catalog,
        "schemaName": schema,
        "tableName": table,
        "columnName": column,
        "action": action,
    }
    if transform_sql is not None:
        body["transformSql"] = transform_sql
    call(ctx, "POST", "/api/role/column-policy/create", body=body)


@column_policy_app.command("update")
def column_policy_update(
    ctx: typer.Context,
    policy_id: str = typer.Argument(..., metavar="ID"),
    action: str = typer.Option(..., "--action"),
    transform_sql: str = typer.Option(None, "--transform-sql"),
):
    body: dict = {"id": policy_id, "action": action}
    if transform_sql is not None:
        body["transformSql"] = transform_sql
    call(ctx, "POST", "/api/role/column-policy/update", body=body)


@column_policy_app.command("delete")
def column_policy_delete(ctx: typer.Context, policy_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/role/column-policy/delete", body={"id": policy_id})
