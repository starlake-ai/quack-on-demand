import sys

import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Your own profile: usage, recent statements and the pools you may use (any session).")


@app.command()
@covers("GET", "/api/profile/usage", {"days": "--days"})
def usage(ctx: typer.Context, days: int = typer.Option(None, "--days", help="Window in days (default 30, max 365).")):
    """Your own daily statement counts."""
    call(ctx, "GET", "/api/profile/usage", params={"days": days})


@app.command()
@covers("GET", "/api/profile/statements", {"limit": "--limit"})
def statements(ctx: typer.Context, limit: int = typer.Option(None, "--limit", help="Max rows (default 50).")):
    """Your own most recent statements."""
    call(ctx, "GET", "/api/profile/statements", params={"limit": limit})


@app.command()
@covers("GET", "/api/profile/pools")
def pools(ctx: typer.Context):
    """The pools you may connect to."""
    call(ctx, "GET", "/api/profile/pools")


@app.command()
@covers("GET", "/api/profile/recoverable", {"tenantDb": "--db", "limit": "--limit"})
def recoverable(
    ctx: typer.Context,
    db: str = typer.Option(..., "--db", help="Database (tenant-db) of your own tenant."),
    limit: int = typer.Option(None, "--limit", help="Max entries (default 50)."),
):
    """Dropped tables of your own database you may recover (free name, a grant on it)."""
    call(ctx, "GET", "/api/profile/recoverable", params={"tenantDb": db, "limit": limit})


@app.command()
@covers(
    "POST",
    "/api/profile/undrop",
    {
        "tenantDb": "--db",
        "schema": "--schema",
        "table": "--table",
        "asName": "--as-name",
        "tableId": "--table-id",
    },
)
def undrop(
    ctx: typer.Context,
    db: str = typer.Option(..., "--db", help="Database (tenant-db) of your own tenant."),
    schema: str = typer.Option(..., "--schema"),
    table: str = typer.Option(..., "--table"),
    as_name: str = typer.Option(None, "--as-name", help="Recover under a different name."),
    table_id: int = typer.Option(
        None,
        "--table-id",
        help="Which drop to recover when the name was dropped more than once (the tableId recoverable lists; default: the newest).",
    ),
):
    """Recover a dropped table of your own database, as you (needs a read grant on its last name and DDL in the schema)."""
    body: dict = {"tenantDb": db, "schema": schema, "table": table}
    if as_name is not None:
        body["asName"] = as_name
    if table_id is not None:
        body["tableId"] = table_id
    call(ctx, "POST", "/api/profile/undrop", body=body)


s3_app = typer.Typer(help="Your own S3 credentials: the keys your statements reach S3 with, each under a scope.")
app.add_typer(s3_app, name="s3")


def _read_secrets(secret_stdin: bool, secret_prompt: bool, token_stdin: bool, token_prompt: bool) -> tuple[str, str | None]:
    """The secret (required) and the session token (optional), never from argv (shell history, `ps`).
    With both on stdin, the secret is its first line and the token its second."""
    if secret_stdin == secret_prompt:
        raise typer.BadParameter("pass exactly one of --secret-stdin / --secret-prompt")
    if token_stdin and token_prompt:
        raise typer.BadParameter("pass at most one of --session-token-stdin / --session-token-prompt")
    token: str | None = None
    if secret_stdin and token_stdin:
        lines = sys.stdin.read().split("\n")
        secret = lines[0].rstrip("\r")
        token = lines[1].rstrip("\r") if len(lines) > 1 else ""
    elif secret_stdin:
        secret = sys.stdin.read().rstrip("\r\n")
    else:
        secret = typer.prompt("Secret access key", hide_input=True, confirmation_prompt=True)
    if token_stdin and not secret_stdin:
        token = sys.stdin.read().rstrip("\r\n")
    elif token_prompt:
        token = typer.prompt("Session token", hide_input=True, confirmation_prompt=True)
    if not secret.strip():
        raise typer.BadParameter("the secret access key read is blank")
    if token is not None and not token.strip():
        raise typer.BadParameter("the session token read is blank")
    return secret, token


@s3_app.command("list")
@covers("POST", "/api/profile/s3/list")
def s3_list(ctx: typer.Context):
    """Your S3 credentials. The secret and the session token are never shown."""
    call(ctx, "POST", "/api/profile/s3/list", body={})


@s3_app.command("add")
@covers(
    "POST",
    "/api/profile/s3/add",
    {
        "scope": "SCOPE",
        "accessKeyId": "--access-key-id",
        "secretAccessKey": "--secret-stdin/--secret-prompt",
        "sessionToken": "--session-token-stdin/--session-token-prompt",
        "region": "--region",
        "endpoint": "--endpoint",
        "urlStyle": "--url-style",
    },
)
def s3_add(
    ctx: typer.Context,
    scope: str = typer.Argument(..., metavar="SCOPE", help="s3://<bucket>/ or s3://<bucket>/<prefix>/: what these keys reach."),
    access_key_id: str = typer.Option(..., "--access-key-id", help="The access key id."),
    secret_stdin: bool = typer.Option(False, "--secret-stdin", help="Read the secret access key from stdin."),
    secret_prompt: bool = typer.Option(False, "--secret-prompt", help="Prompt for the secret access key (hidden, confirmed)."),
    session_token_stdin: bool = typer.Option(False, "--session-token-stdin", help="Read a session token from stdin (after the secret's line when both are)."),
    session_token_prompt: bool = typer.Option(False, "--session-token-prompt", help="Prompt for a session token (hidden, confirmed)."),
    region: str = typer.Option(None, "--region", help="The bucket's region."),
    endpoint: str = typer.Option(None, "--endpoint", help="host[:port] (http:// for plain HTTP): only one the operator allows (QOD_USER_S3_ALLOWED_ENDPOINTS)."),
    url_style: str = typer.Option(None, "--url-style", help="path | vhost."),
):
    """Register S3 keys of your own for SCOPE (a scope you already hold is replaced). Your statements
    then reach s3:// URLs under it, signed with these keys; never a database's data folder."""
    secret, token = _read_secrets(secret_stdin, secret_prompt, session_token_stdin, session_token_prompt)
    body: dict = {"scope": scope, "accessKeyId": access_key_id, "secretAccessKey": secret}
    for key, value in (("sessionToken", token), ("region", region), ("endpoint", endpoint), ("urlStyle", url_style)):
        if value is not None:
            body[key] = value
    call(ctx, "POST", "/api/profile/s3/add", body=body)


@s3_app.command("remove")
@covers("POST", "/api/profile/s3/remove", {"id": "ID"})
def s3_remove(ctx: typer.Context, credential_id: str = typer.Argument(..., metavar="ID", help="The credential id (from `qod profile s3 list`).")):
    """Remove one of your S3 credentials."""
    call(ctx, "POST", "/api/profile/s3/remove", body={"id": credential_id})
