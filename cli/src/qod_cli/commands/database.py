import typer

from ..registry import covers
from ._run import call, kv_pairs

app = typer.Typer(help="Tenant databases (DuckLake catalogs).")


@app.command("list")
@covers("GET", "/api/database/list", {"tenant": "--tenant"})
def list_(ctx: typer.Context, tenant: str = typer.Option(..., "--tenant")):
    call(ctx, "GET", "/api/database/list", params={"tenant": tenant})


@app.command()
@covers(
    "POST",
    "/api/database/create",
    {
        "tenant": "--tenant",
        "name": "--name",
        "kind": "--kind",
        "metastore": "--metastore",
        "dataPath": "--data-path",
        "objectStore": "--object-store",
        "defaultDatabase": "--default-database",
        "defaultSchema": "--default-schema",
        "initSql": "--init-sql",
        "managedStorage": "--managed-storage",
        "encrypted": "--encrypted",
        "encryptionKey": "--encryption-key",
    },
)
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
    managed_storage: bool = typer.Option(
        False,
        "--managed-storage",
        help="Provision a managed data path (exclusive with --data-path/--object-store)",
    ),
    encrypted: bool = typer.Option(
        False,
        "--encrypted",
        help="Encrypt data at rest. Cannot be changed later: create a new database to change it.",
    ),
    encryption_key: str = typer.Option(
        None,
        "--encryption-key",
        help=(
            "duckdb-file only: supply your own key instead of letting QoD mint one. "
            "It is never readable back through the API. Lose it and the database is unreadable."
        ),
    ),
):
    meta = kv_pairs(metastore)
    if kind == "duckdb-file":
        # TenantDb.DuckDbFileRequiredKeys is {dbName, schemaName} and the server refuses
        # without them, but neither is a choice for a plain file: the catalog alias is the
        # database's own name and DuckDB's default schema is `main`. Requiring the caller to
        # spell out DuckLake vocabulary to attach a file was pure friction. Anything the
        # caller passed still wins. `qod serve` already sets dbName to the same raw suffix
        # for this kind (serve_target.py); this makes the bare command agree with it. The
        # server stores a duckdb-file metastore verbatim (PoolSupervisor.createTenantDb
        # force-sets dbName only for DuckLake), so this value is what the node ATTACHes as.
        meta.setdefault("dbName", name)
        meta.setdefault("schemaName", "main")
    body = {
        "tenant": tenant,
        "name": name,
        "kind": kind,
        "metastore": meta,
        "dataPath": data_path,
        "objectStore": kv_pairs(object_store),
        "initSql": init_sql,
    }
    if default_database is not None:
        body["defaultDatabase"] = default_database
    if default_schema is not None:
        body["defaultSchema"] = default_schema
    if managed_storage:
        body["managedStorage"] = True
    if encrypted:
        body["encrypted"] = True
    if encryption_key:
        body["encryptionKey"] = encryption_key
    call(ctx, "POST", "/api/database/create", body=body)


@app.command()
@covers(
    "POST",
    "/api/database/update",
    {
        "tenant": "--tenant",
        "name": "--name",
        "metastore": "--metastore",
        "objectStore": "--object-store",
        "defaultDatabase": "--default-database",
        "defaultSchema": "--default-schema",
        "initSql": "--init-sql",
    },
)
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
@covers(
    "POST",
    "/api/database/delete",
    {"tenant": "--tenant", "name": "--name", "purgeManagedData": "--purge-managed-data"},
)
def delete(
    ctx: typer.Context,
    tenant: str = typer.Option(..., "--tenant"),
    name: str = typer.Option(..., "--name"),
    purge_managed_data: bool = typer.Option(
        False,
        "--purge-managed-data",
        help="Purge managed object storage immediately instead of after the retention window",
    ),
):
    body = {"tenant": tenant, "name": name}
    if purge_managed_data:
        body["purgeManagedData"] = True
    call(ctx, "POST", "/api/database/delete", body=body)
