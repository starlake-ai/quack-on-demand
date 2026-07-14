import typer

from ._run import call, kv_pairs

app = typer.Typer(help="Tenant databases (DuckLake catalogs).")


@app.command("list")
def list_(ctx: typer.Context, tenant: str = typer.Option(..., "--tenant")):
    call(ctx, "GET", "/api/database/list", params={"tenant": tenant})


@app.command()
def create(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    name: str = typer.Option(..., "--name", help="Suffix; server composes <tenant>_<name>."),
    kind: str = typer.Option("ducklake", "--kind", help="ducklake|duckdb-file|memory"),
    metastore: list[str] = typer.Option(None, "--metastore", help="KEY=VALUE, repeatable."),
    data_path: str = typer.Option("", "--data-path"),
    object_store: list[str] = typer.Option(None, "--object-store", help="KEY=VALUE, repeatable."),
    default_database: str = typer.Option(None, "--default-database"),
    default_schema: str = typer.Option(None, "--default-schema"),
    init_sql: str = typer.Option("", "--init-sql"),
):
    body = {
        "tenant": tenant,
        "name": name,
        "kind": kind,
        "metastore": kv_pairs(metastore),
        "dataPath": data_path,
        "objectStore": kv_pairs(object_store),
        "initSql": init_sql,
    }
    if default_database is not None:
        body["defaultDatabase"] = default_database
    if default_schema is not None:
        body["defaultSchema"] = default_schema
    call(ctx, "POST", "/api/database/create", body=body)


@app.command()
def update(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    name: str = typer.Option(..., "--name"),
    metastore: list[str] = typer.Option(None, "--metastore", help="KEY=VALUE; omit = unchanged."),
    object_store: list[str] = typer.Option(None, "--object-store"),
    default_database: str = typer.Option(None, "--default-database"),
    default_schema: str = typer.Option(None, "--default-schema"),
    init_sql: str = typer.Option(None, "--init-sql"),
):
    body: dict = {"tenant": tenant, "name": name}
    if metastore is not None:
        body["metastore"] = kv_pairs(metastore)
    if object_store is not None:
        body["objectStore"] = kv_pairs(object_store)
    if default_database is not None:
        body["defaultDatabase"] = default_database
    if default_schema is not None:
        body["defaultSchema"] = default_schema
    if init_sql is not None:
        body["initSql"] = init_sql
    call(ctx, "POST", "/api/database/update", body=body)


@app.command()
def delete(ctx: typer.Context, tenant: str = typer.Option(..., "--tenant"), name: str = typer.Option(..., "--name")):
    call(ctx, "POST", "/api/database/delete", body={"tenant": tenant, "name": name})
