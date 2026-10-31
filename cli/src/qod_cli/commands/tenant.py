import sys

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


def _read_client_secret(stdin: bool, prompt: bool, clear: bool) -> str | None:
    """The tenant's OIDC client secret, read without it ever touching argv (shell history, `ps`).

    None keeps the stored secret (the manager drops it when issuerUrl changes); "" removes it.
    Mirrors federation's _read_password.
    """
    if sum((stdin, prompt, clear)) > 1:
        raise typer.BadParameter(
            "pass at most one of --client-secret-stdin / --client-secret-prompt / --clear-client-secret"
        )
    if clear:
        return ""
    if stdin:
        value = sys.stdin.read().rstrip("\r\n")
    elif prompt:
        value = typer.prompt("Client secret", hide_input=True, confirmation_prompt=True)
    else:
        return None
    if not value.strip():
        raise typer.BadParameter("the client secret read is blank")
    return value


@app.command("set-auth")
@covers(
    "POST",
    "/api/tenant/setAuth",
    {
        "name": "NAME",
        "authProvider": "--auth-provider",
        "authConfig": "--auth-config",
        "clientSecret": "--client-secret-stdin/--client-secret-prompt/--clear-client-secret",
    },
)
def set_auth(
    ctx: typer.Context,
    name: str = typer.Argument(...),
    auth_provider: str = typer.Option(..., "--auth-provider"),
    auth_config: list[str] = typer.Option(
        None, "--auth-config",
        help="KEY=VALUE, repeatable; replaces the stored config. Never put the client secret here.",
    ),
    client_secret_stdin: bool = typer.Option(
        False, "--client-secret-stdin", help="Read the OIDC client secret from stdin (write-only).",
    ),
    client_secret_prompt: bool = typer.Option(
        False, "--client-secret-prompt", help="Prompt for the OIDC client secret (hidden, confirmed).",
    ),
    clear_client_secret: bool = typer.Option(
        False, "--clear-client-secret", help="Remove the stored OIDC client secret.",
    ),
):
    """Set the tenant's sign-in provider and config. Without a secret flag the stored client secret
    is kept only while issuerUrl stays the same."""
    secret = _read_client_secret(client_secret_stdin, client_secret_prompt, clear_client_secret)
    body: dict = {"name": name, "authProvider": auth_provider, "authConfig": kv_pairs(auth_config)}
    if secret is not None:
        body["clientSecret"] = secret
    call(ctx, "POST", "/api/tenant/setAuth", body=body)


@app.command("set-acl")
@covers(
    "POST",
    "/api/tenant/setAcl",
    {
        "name": "NAME",
        "mode": "--mode",
        "opaUrl": "--opa-url",
        "opaPolicyPath": "--opa-policy-path",
        "opaToken": "--opa-token",
        "sendStatementText": "--send-statement-text",
    },
)
def set_acl(
    ctx: typer.Context,
    name: str = typer.Argument(...),
    mode: str = typer.Option(None, "--mode", help="qod | opa | '' (manager default)."),
    opa_url: str = typer.Option(None, "--opa-url", help="OPA base URL; '' clears (falls back to QOD_OPA_URL)."),
    opa_policy_path: str = typer.Option(None, "--opa-policy-path", help="Default qod/authz; '' clears."),
    opa_token: str = typer.Option(None, "--opa-token", help="Bearer for OPA; write-only; '' clears."),
    send_statement_text: bool = typer.Option(
        None,
        "--send-statement-text/--no-send-statement-text",
        help="Kept for compatibility, no effect: the node never sends SQL text in the OPA input.",
    ),
):
    """Set the tenant's data-access authorization (QoD grants or the tenant's OPA). Omitted options keep their value."""
    body: dict = {"name": name}
    if mode is not None:
        body["mode"] = mode
    if opa_url is not None:
        body["opaUrl"] = opa_url
    if opa_policy_path is not None:
        body["opaPolicyPath"] = opa_policy_path
    if opa_token is not None:
        body["opaToken"] = opa_token
    if send_statement_text is not None:
        body["sendStatementText"] = send_statement_text
    call(ctx, "POST", "/api/tenant/setAcl", body=body)


@app.command("opa-test")
@covers(
    "POST",
    "/api/tenant/opaTest",
    {"tenant": "TENANT", "pool": "--pool", "user": "--user", "sql": "--sql"},
)
def opa_test(
    ctx: typer.Context,
    tenant: str = typer.Argument(..., metavar="TENANT"),
    pool: str = typer.Option(..., "--pool"),
    user: str = typer.Option(..., "--user"),
    sql: str = typer.Option(None, "--sql", help="Omit to test pool access (connect). With it, the statement runs on a node of the pool (which must be running) as the user, exactly as live, and is judged, never executed."),
):
    """Dry-run an OPA decision: prints the exact input and the decision. Executes nothing."""
    body: dict = {"tenant": tenant, "pool": pool, "user": user}
    if sql is not None:
        body["sql"] = sql
    call(ctx, "POST", "/api/tenant/opaTest", body=body)


location_app = typer.Typer(
    help="Authorized locations: the local folders a tenant's users may read and write files in "
    "(S3 is per user: qod profile s3)."
)
app.add_typer(location_app, name="location")


@location_app.command("list")
@covers("POST", "/api/tenant/location/list", {"tenant": "TENANT"})
def location_list(ctx: typer.Context, tenant: str = typer.Argument(..., metavar="TENANT")):
    """List the tenant's authorized local folders."""
    call(ctx, "POST", "/api/tenant/location/list", body={"tenant": tenant})


@location_app.command("add")
@covers("POST", "/api/tenant/location/add", {"tenant": "TENANT", "location": "LOCATION"})
def location_add(
    ctx: typer.Context,
    tenant: str = typer.Argument(..., metavar="TENANT"),
    location: str = typer.Argument(..., metavar="LOCATION", help="An absolute folder under <QOD_TENANT_FILES_ROOT>/<tenant>/."),
):
    """Authorize a local folder for the tenant's users. A database's data folder stays unreachable."""
    call(ctx, "POST", "/api/tenant/location/add", body={"tenant": tenant, "location": location})


@location_app.command("remove")
@covers("POST", "/api/tenant/location/remove", {"tenant": "TENANT", "id": "ID"})
def location_remove(
    ctx: typer.Context,
    tenant: str = typer.Argument(..., metavar="TENANT"),
    location_id: str = typer.Argument(..., metavar="ID", help="The location id (from `qod tenant location list`)."),
):
    """Remove an authorized location."""
    call(ctx, "POST", "/api/tenant/location/remove", body={"tenant": tenant, "id": location_id})
