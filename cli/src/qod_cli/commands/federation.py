import json

import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Federated sources per (tenant, tenant-db).")
secret_app = typer.Typer(help="Secrets referenced by a federated source's setup SQL.")
app.add_typer(secret_app, name="secret")

TENANT = typer.Argument(..., metavar="TENANT")
DB = typer.Argument(..., metavar="DB")
ALIAS = typer.Argument(..., metavar="ALIAS")


def _base(tenant: str, db: str) -> str:
    return f"/api/tenants/{tenant}/tenant-dbs/{db}/federated-sources"


@app.command("list")
@covers(
    "GET",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources",
    {"tenant": "TENANT", "tenantDb": "DB"},
)
def list_(ctx: typer.Context, tenant: str = TENANT, db: str = DB):
    call(ctx, "GET", _base(tenant, db))


@app.command()
@covers(
    "GET",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS"},
)
def get(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "GET", f"{_base(tenant, db)}/{alias}")


_TYPE_WIRE = {"sql": "sql", "iceberg-rest": "iceberg_rest", "iceberg_rest": "iceberg_rest"}


def _iceberg_config(
    raw: str | None,
    uri: str | None,
    warehouse: str | None,
    auth: str | None,
    endpoint_type: str | None,
    client_id: str | None,
    client_secret: str | None,
    oauth2_server_uri: str | None,
    oauth2_scope: str | None,
    oauth2_grant_type: str | None,
    token: str | None,
) -> dict:
    """Assemble the typed config. --config wins whole; otherwise build it from the flags.

    Credential flags are meant to carry {{secret.NAME}} placeholders, not literal values: the
    manager resolves those against the source's secrets at node spawn, so a real secret never
    lands in a shell history or a CI log.
    """
    if raw is not None:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise typer.BadParameter(f"--config is not valid JSON: {exc}") from exc
        if not isinstance(parsed, dict):
            raise typer.BadParameter("--config must be a JSON object")
        return parsed

    cfg: dict = {}
    if uri is not None:
        cfg["uri"] = uri
    if warehouse is not None:
        cfg["warehouse"] = warehouse
    if auth is not None:
        cfg["authType"] = auth
    if endpoint_type is not None:
        cfg["endpointType"] = endpoint_type
    for key, value in (
        ("clientId", client_id),
        ("clientSecret", client_secret),
        ("oauth2ServerUri", oauth2_server_uri),
        ("oauth2Scope", oauth2_scope),
        ("oauth2GrantType", oauth2_grant_type),
        ("token", token),
    ):
        if value is not None:
            cfg[key] = value
    if not cfg.get("warehouse"):
        raise typer.BadParameter(
            "--warehouse is required for --type iceberg-rest (or pass the whole --config JSON)"
        )
    return cfg


@app.command()
@covers(
    "POST",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "--alias",
        "setupSql": "--setup-sql",
        "description": "--description",
        "disabled": "--disabled",
        "sourceType": "--type",
        "config": "--config",
        "readOnly": "--read-only",
    },
)
def create(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = typer.Option(..., "--alias"),
    setup_sql: str = typer.Option(None, "--setup-sql", help="sql sources only."),
    description: str = typer.Option(None, "--description"),
    disabled: bool = typer.Option(False, "--disabled"),
    source_type: str = typer.Option("sql", "--type", help="sql | iceberg-rest"),
    config: str = typer.Option(None, "--config", help="Whole typed config as JSON."),
    read_only: bool = typer.Option(
        None, "--read-only/--no-read-only",
        help="Deny writes to this catalog. Defaults on for iceberg-rest.",
    ),
    uri: str = typer.Option(None, "--uri", help="Iceberg REST endpoint."),
    warehouse: str = typer.Option(None, "--warehouse"),
    auth: str = typer.Option(None, "--auth", help="none | oauth2 | token | sigv4"),
    endpoint_type: str = typer.Option(None, "--endpoint-type", help="glue | s3_tables"),
    client_id: str = typer.Option(None, "--client-id"),
    client_secret: str = typer.Option(None, "--client-secret"),
    oauth2_server_uri: str = typer.Option(None, "--oauth2-server-uri"),
    oauth2_scope: str = typer.Option(None, "--oauth2-scope"),
    oauth2_grant_type: str = typer.Option(None, "--oauth2-grant-type"),
    token: str = typer.Option(None, "--token"),
):
    wire = _TYPE_WIRE.get(source_type)
    if wire is None:
        raise typer.BadParameter(f"--type must be sql or iceberg-rest, got '{source_type}'")

    body: dict = {"alias": alias, "disabled": disabled}
    if description is not None:
        body["description"] = description
    if read_only is not None:
        body["readOnly"] = read_only

    if wire == "iceberg_rest":
        if setup_sql is not None:
            raise typer.BadParameter("--setup-sql is for --type sql; use the iceberg flags")
        body["sourceType"] = "iceberg_rest"
        body["config"] = _iceberg_config(
            config, uri, warehouse, auth, endpoint_type, client_id, client_secret,
            oauth2_server_uri, oauth2_scope, oauth2_grant_type, token,
        )
    else:
        if setup_sql is None:
            raise typer.BadParameter("--setup-sql is required for --type sql")
        body["setupSql"] = setup_sql

    call(ctx, "POST", _base(tenant, db), body=body)


@app.command()
@covers(
    "DELETE",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS"},
)
def delete(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "DELETE", f"{_base(tenant, db)}/{alias}")


@secret_app.command("list")
@covers(
    "GET",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS"},
)
def secret_list(ctx: typer.Context, tenant: str = TENANT, db: str = DB, alias: str = ALIAS):
    call(ctx, "GET", f"{_base(tenant, db)}/{alias}/secrets")


@secret_app.command("set")
@covers(
    "PUT",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets",
    {
        "tenant": "TENANT",
        "tenantDb": "DB",
        "alias": "ALIAS",
        "name": "--name",
        "value": "--value",
        "externalRef": "--external-ref",
    },
)
def secret_set(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = ALIAS,
    name: str = typer.Option(..., "--name"),
    value: str = typer.Option(None, "--value", help="Inline value stored in Postgres."),
    external_ref: str = typer.Option(None, "--external-ref", help="e.g. env:PGPASS."),
):
    if (value is None) == (external_ref is None):
        raise typer.BadParameter("pass exactly one of --value / --external-ref")
    body: dict = {"name": name}
    if value is not None:
        body["value"] = value
    if external_ref is not None:
        body["externalRef"] = external_ref
    call(ctx, "PUT", f"{_base(tenant, db)}/{alias}/secrets", body=body)


@secret_app.command("delete")
@covers(
    "DELETE",
    "/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources/{alias}/secrets/{name}",
    {"tenant": "TENANT", "tenantDb": "DB", "alias": "ALIAS", "name": "NAME"},
)
def secret_delete(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = ALIAS,
    name: str = typer.Argument(..., metavar="NAME"),
):
    call(ctx, "DELETE", f"{_base(tenant, db)}/{alias}/secrets/{name}")
