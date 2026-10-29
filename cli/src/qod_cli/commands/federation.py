import json
import re
import sys

import typer

from ..registry import covers
from ..rest import ApiError, RestClient
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


_TYPE_WIRE = {
    "sql": "sql",
    "iceberg-rest": "iceberg_rest",
    "iceberg_rest": "iceberg_rest",
    "postgres": "postgres",
    "mysql": "mysql",
}

_SECRET_PLACEHOLDER = re.compile(r"^\{\{secret\.[A-Za-z0-9_]+\}\}$")
_SECRET_NAME = re.compile(r"^[A-Za-z0-9_]+$")


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
    aws_credentials: str | None = None,
    aws_region: str | None = None,
    aws_key_id: str | None = None,
    aws_secret_access_key: str | None = None,
    aws_session_token: str | None = None,
    aws_scope: list[str] | None = None,
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
        ("awsCredentials", aws_credentials),
        ("awsRegion", aws_region),
        ("awsKeyId", aws_key_id),
        ("awsSecretAccessKey", aws_secret_access_key),
        ("awsSessionToken", aws_session_token),
    ):
        if value is not None:
            cfg[key] = value
    if aws_scope:
        cfg["awsScope"] = list(aws_scope)
    if not cfg.get("warehouse"):
        raise typer.BadParameter(
            "--warehouse is required for --type iceberg-rest (or pass the whole --config JSON)"
        )
    return cfg


def _rdbms_connection(
    wire: str,
    raw: str | None,
    host: str | None,
    port: int | None,
    database: str | None,
    user: str | None,
    password: str | None,
    schema: str | None,
    ssl_mode: str | None,
) -> dict:
    """Assemble the typed postgres / mysql connection. --connection wins whole; otherwise build it
    from the flags. `password` here is always a {{secret.NAME}} placeholder (the caller resolved
    --password-stdin / --password-prompt into one); the manager refuses a literal.
    """
    if raw is not None:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise typer.BadParameter(f"--connection is not valid JSON: {exc}") from exc
        if not isinstance(parsed, dict):
            raise typer.BadParameter("--connection must be a JSON object")
        return parsed

    conn: dict = {}
    for key, value in (
        ("host", host),
        ("database", database),
        ("user", user),
        ("password", password),
        ("schema", schema),
        ("sslMode", ssl_mode),
    ):
        if value is not None:
            conn[key] = value
    if port is not None:
        conn["port"] = port
    missing = [flag for flag, key in (("--host", "host"), ("--database", "database"),
                                      ("--user", "user")) if not conn.get(key)]
    if missing:
        raise typer.BadParameter(
            f"{', '.join(missing)} required for --type {wire} (or pass the whole --connection JSON)"
        )
    if "password" not in conn:
        raise typer.BadParameter(
            f"--type {wire} needs a password: --password-stdin, --password-prompt, or "
            "--password '{{secret.NAME}}' naming an existing secret"
        )
    return conn


def _read_password(stdin: bool, prompt: bool) -> str | None:
    """The literal password value, read without it ever touching argv (shell history, `ps`)."""
    if stdin and prompt:
        raise typer.BadParameter("pass at most one of --password-stdin / --password-prompt")
    if stdin:
        value = sys.stdin.read().rstrip("\r\n")
    elif prompt:
        value = typer.prompt("Password", hide_input=True, confirmation_prompt=True)
    else:
        return None
    if not value.strip():
        raise typer.BadParameter("the password read is blank")
    return value


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
        "connection": "--connection",
    },
)
def create(
    ctx: typer.Context,
    tenant: str = TENANT,
    db: str = DB,
    alias: str = typer.Option(..., "--alias"),
    setup_sql: str = typer.Option(
        None,
        "--setup-sql",
        help="sql sources only; a sql source is a superuser's to create or change, and every "
        "{{secret.NAME}} in it must sit inside a single-quoted literal ('...').",
    ),
    description: str = typer.Option(None, "--description"),
    disabled: bool = typer.Option(False, "--disabled"),
    source_type: str = typer.Option(
        "sql", "--type", help="sql | iceberg-rest | postgres | mysql",
    ),
    config: str = typer.Option(None, "--config", help="Whole typed config as JSON."),
    read_only: bool = typer.Option(
        None, "--read-only/--no-read-only",
        help="Deny writes to this catalog. Defaults on for iceberg-rest, postgres and mysql.",
    ),
    uri: str = typer.Option(None, "--uri", help="Iceberg REST endpoint."),
    warehouse: str = typer.Option(
        None, "--warehouse",
        help="Catalog warehouse; the AWS account id for glue, the table bucket ARN for s3_tables.",
    ),
    auth: str = typer.Option(None, "--auth", help="none | oauth2 | token | sigv4"),
    endpoint_type: str = typer.Option(None, "--endpoint-type", help="glue | s3_tables"),
    client_id: str = typer.Option(None, "--client-id"),
    client_secret: str = typer.Option(None, "--client-secret"),
    oauth2_server_uri: str = typer.Option(None, "--oauth2-server-uri"),
    oauth2_scope: str = typer.Option(None, "--oauth2-scope"),
    oauth2_grant_type: str = typer.Option(None, "--oauth2-grant-type"),
    token: str = typer.Option(None, "--token"),
    aws_credentials: str = typer.Option(
        None, "--aws-credentials",
        help="sigv4 / glue / s3_tables: config (static keys) | credential_chain.",
    ),
    aws_region: str = typer.Option(
        None, "--aws-region", help="Required for glue and sigv4; s3_tables reads it off the ARN.",
    ),
    aws_key_id: str = typer.Option(None, "--aws-key-id"),
    aws_secret_access_key: str = typer.Option(
        None, "--aws-secret-access-key", help="A {{secret.NAME}} placeholder.",
    ),
    aws_session_token: str = typer.Option(
        None, "--aws-session-token", help="A {{secret.NAME}} placeholder.",
    ),
    aws_scope: list[str] = typer.Option(
        None, "--aws-scope",
        help="s3:// prefix holding the catalog's table data; repeatable, required with "
        "--aws-credentials.",
    ),
    connection: str = typer.Option(
        None, "--connection", help="postgres / mysql: the whole typed connection as JSON.",
    ),
    host: str = typer.Option(None, "--host", help="postgres / mysql: host name or IP address."),
    port: int = typer.Option(
        None, "--port", help="postgres / mysql: defaults to 5432 / 3306.",
    ),
    database: str = typer.Option(None, "--database", help="postgres / mysql: database name."),
    user: str = typer.Option(None, "--user", help="postgres / mysql: user name."),
    password: str = typer.Option(
        None, "--password",
        help="postgres / mysql: a {{secret.NAME}} placeholder naming an existing secret of this "
        "source. A literal is refused; use --password-stdin or --password-prompt for a value.",
    ),
    password_stdin: bool = typer.Option(
        False, "--password-stdin",
        help="postgres / mysql: read the password from stdin and store it as this source's "
        "secret (see --password-secret).",
    ),
    password_prompt: bool = typer.Option(
        False, "--password-prompt",
        help="postgres / mysql: prompt for the password (hidden) and store it as this source's "
        "secret.",
    ),
    password_secret: str = typer.Option(
        "PASSWORD", "--password-secret",
        help="Name of the secret --password-stdin / --password-prompt stores the value under.",
    ),
    schema: str = typer.Option(
        None, "--schema", help="postgres only: attach just this schema.",
    ),
    ssl_mode: str = typer.Option(
        None, "--ssl-mode",
        help="postgres: disable | allow | prefer | require | verify-ca | verify-full; "
        "mysql: disabled | preferred | required | verify_ca | verify_identity.",
    ),
):
    wire = _TYPE_WIRE.get(source_type)
    if wire is None:
        raise typer.BadParameter(
            f"--type must be sql, iceberg-rest, postgres or mysql, got '{source_type}'"
        )

    body: dict = {"alias": alias, "disabled": disabled}
    if description is not None:
        body["description"] = description
    if read_only is not None:
        body["readOnly"] = read_only

    rdbms_flags = (connection, host, port, database, user, password, schema, ssl_mode)
    if wire not in ("postgres", "mysql") and (
        any(v is not None for v in rdbms_flags) or password_stdin or password_prompt
    ):
        raise typer.BadParameter(
            "--connection / --host / --port / --database / --user / --password* / --schema / "
            "--ssl-mode are for --type postgres or mysql"
        )

    secret_value: str | None = None
    if wire in ("postgres", "mysql"):
        if setup_sql is not None or config is not None:
            raise typer.BadParameter(
                f"--setup-sql / --config are not for --type {wire}; use the connection flags"
            )
        if connection is not None and (
            any(v is not None for v in (host, port, database, user, password, schema, ssl_mode))
            or password_stdin or password_prompt
        ):
            raise typer.BadParameter(
                "--connection carries the whole connection, password placeholder included; it "
                "cannot be combined with --host / --port / --database / --user / --password* / "
                "--schema / --ssl-mode"
            )
        secret_value = _read_password(password_stdin, password_prompt)
        if secret_value is not None:
            if password is not None:
                raise typer.BadParameter(
                    "--password names an existing secret; it cannot be combined with "
                    "--password-stdin / --password-prompt"
                )
            if not _SECRET_NAME.match(password_secret):
                raise typer.BadParameter("--password-secret must be letters, digits, underscore")
            password = "{{secret." + password_secret + "}}"
        elif password is not None and not _SECRET_PLACEHOLDER.match(password.strip()):
            raise typer.BadParameter(
                "--password takes a {{secret.NAME}} placeholder, never the password itself "
                "(it would land in your shell history); use --password-stdin or --password-prompt"
            )
        body["sourceType"] = wire
        body["connection"] = _rdbms_connection(
            wire, connection, host, port, database, user, password, schema, ssl_mode,
        )
    elif wire == "iceberg_rest":
        if setup_sql is not None:
            raise typer.BadParameter("--setup-sql is for --type sql; use the iceberg flags")
        body["sourceType"] = "iceberg_rest"
        body["config"] = _iceberg_config(
            config, uri, warehouse, auth, endpoint_type, client_id, client_secret,
            oauth2_server_uri, oauth2_scope, oauth2_grant_type, token,
            aws_credentials, aws_region, aws_key_id, aws_secret_access_key,
            aws_session_token, aws_scope,
        )
    else:
        if setup_sql is None:
            raise typer.BadParameter("--setup-sql is required for --type sql")
        body["setupSql"] = setup_sql

    if secret_value is None:
        call(ctx, "POST", _base(tenant, db), body=body)
        return
    _create_with_secret(ctx, tenant, db, body, password_secret, secret_value)


def _source_exists(ctx: typer.Context, tenant: str, db: str, alias: str) -> bool:
    """Whether source `alias` exists: its secret listing answers 200, a new source 404s. Any
    other failure stops the command rather than being read as "new"."""
    try:
        RestClient(ctx.obj.settings).request("GET", f"{_base(tenant, db)}/{alias}/secrets")
    except ApiError as exc:
        if exc.status == 404:
            return False
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
    return True


def _create_with_secret(
    ctx: typer.Context, tenant: str, db: str, body: dict, secret: str, value: str
) -> None:
    """Save the source and the password secret its placeholder names, never leaving an ENABLED
    source that references a missing secret: a pool spawning then would fail to resolve it, which
    fails the whole tenant-db's federation blob.

    An existing source already holds the secret endpoint, so the secret is written FIRST and the
    source saved after it: its placeholder resolves at every instant and it is never disabled (a
    rotation, or a move to a new secret name). A new source has no secret endpoint yet, so it is
    saved disabled (disabled sources are in no blob), the secret written, and the source saved
    again as asked.
    """
    alias = body["alias"].strip().lower()
    secret_path = f"{_base(tenant, db)}/{alias}/secrets"
    fix = (
        f"`qod federation secret set {tenant} {db} {alias} --name {secret} --value ...`"
    )
    if _source_exists(ctx, tenant, db, alias):
        call(ctx, "PUT", secret_path, body={"name": secret, "value": value}, quiet=True)
        call(ctx, "POST", _base(tenant, db), body=body)
        return

    call(ctx, "POST", _base(tenant, db), body={**body, "disabled": True}, quiet=True)
    try:
        call(ctx, "PUT", secret_path, body={"name": secret, "value": value}, quiet=True)
    except typer.Exit:
        typer.echo(
            f"error: the source was saved DISABLED and its password secret was not; set it with "
            f"{fix}, then re-run this command",
            err=True,
        )
        raise
    try:
        call(ctx, "POST", _base(tenant, db), body=body)
    except typer.Exit:
        typer.echo(
            "error: the source and its password are saved, but the source is still DISABLED; "
            "re-run this command to enable it",
            err=True,
        )
        raise


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
