"""`qod setup`: persist the env vars `qod start` reads, so you don't have to
`export` them (or re-type flags) every time you run `uvx qod start`.

Storage: the same TOML file `qod auth login` writes CLI connection profiles
to (QOD_CONFIG_FILE overrides the path), under a separate `[start]` table -
see qod_cli.config.load_start_env/save_start_env. Precedence at `qod start`
time is file < real process env, so a shell `export QOD_PG_HOST=...` (or a
docker-compose-style `.env` sourced into the shell) still wins over this.
"""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
import sys

import typer

from .. import admin_password as admin_pw
from ..config import REMOVED_API_KEY, config_path, load_start_env, save_start_env
from ..output import render

# (env var, CLI flag label, prompt text, default) - the knobs `qod start`
# actually reads today (_pg_coords in commands/start.py, plus the
# QOD_ADMIN_USERNAME/QOD_AUTH_DB_ENABLED/PROXY_TLS_ENABLED vars that
# application.conf honors - see CLAUDE.md "Every scalar ... accepts a QOD_*
# env-var override"). --set covers anything not on this short list.
_PROMPTS: tuple[tuple[str, str, str], ...] = (
    ("QOD_PG_HOST", "Postgres host", "localhost"),
    ("QOD_PG_PORT", "Postgres port", "5432"),
    ("QOD_PG_USER", "Postgres user", "postgres"),
    ("QOD_PG_PASSWORD", "Postgres password", "azizam"),
    ("QOD_PG_DBNAME", "Control-plane database", "qod"),
    ("QOD_ADMIN_USERNAME", "Admin username", "admin@localhost.local,admin"),
    ("QOD_AUTH_DB_ENABLED", "Enable DB-backed auth (true/false; false needs another "
     "authenticator)", "true"),
    ("PROXY_TLS_ENABLED", "Enable FlightSQL edge TLS (true/false)", "true"),
)
# REMOVED_API_KEY stays masked: an earlier CLI may have stored one in [start].
_SECRET_KEYS = {"QOD_PG_PASSWORD", admin_pw.KEY, REMOVED_API_KEY, "QOD_SESSION_JWT_SECRET"}


def _redact(key: str, value: str) -> str:
    if key not in _SECRET_KEYS or not value:
        return value
    return "*" * min(len(value), 8)


_CONNECTION_KEYS = {
    "QOD_PG_HOST",
    "QOD_PG_PORT",
    "QOD_PG_USER",
    "QOD_PG_PASSWORD",
    "QOD_PG_DBNAME",
}


def _check_connection(merged: dict[str, str]) -> tuple[bool, str]:
    """Best-effort Postgres reachability check against the EFFECTIVE config
    (stored values merged with this invocation's), run before anything is
    persisted. Two tiers: a plain TCP connect (no dependencies) catches wrong
    host/port/firewall; when `psql` is on PATH - the same opportunistic
    convention `qod start` uses for control-plane pre-creation - a
    `SELECT 1` against the maintenance DB verifies the credentials too.
    Credentials ride env vars (PGPASSWORD), never argv."""
    host = merged.get("QOD_PG_HOST", "localhost")
    port = merged.get("QOD_PG_PORT", "5432")
    try:
        with socket.create_connection((host, int(port)), timeout=2):
            pass
    except (OSError, ValueError) as e:
        return False, f"cannot reach {host}:{port} ({e})"
    if shutil.which("psql") is None:
        return True, f"{host}:{port} reachable (credentials unverified: psql not on PATH)"
    env = {
        **os.environ,
        "PGHOST": host,
        "PGPORT": port,
        "PGUSER": merged.get("QOD_PG_USER", "postgres"),
        "PGPASSWORD": merged.get("QOD_PG_PASSWORD", ""),
        "PGCONNECT_TIMEOUT": "5",
    }
    try:
        proc = subprocess.run(
            ["psql", "--dbname", "postgres", "--no-psqlrc", "-tAc", "SELECT 1"],
            env=env,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except subprocess.TimeoutExpired:
        return False, f"{host}:{port} reachable but psql check timed out"
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip().splitlines()
        return False, f"{host}:{port} reachable but login failed: {detail[-1] if detail else 'unknown error'}"
    return True, f"connection check ok ({host}:{port})"


# Saved with QOD_AUTH_DB_ENABLED=false: the manager boots only when something else can
# authenticate FlightSQL clients and create a session (BootPreflight).
_NO_DB_AUTH_NOTE = (
    "note: QOD_AUTH_DB_ENABLED=false: the manager refuses to boot unless another "
    "authenticator is configured: a Keycloak or Azure password backend "
    "(QOD_AUTH_KEYCLOAK_ENABLED / QOD_AUTH_AZURE_ENABLED), or a bearer provider "
    "(JWT_SECRET_KEY / JWT_PUBLIC_KEY_PATH, QOD_AUTH_GOOGLE_ENABLED, QOD_AUTH_AWS_ENABLED) "
    "together with system-scope OIDC sign-in (QOD_MGMT_IDENTITY_SOURCE=oidc with "
    "QOD_MGMT_OIDC_ISSUER_URL and QOD_MGMT_OIDC_CLIENT_ID)."
)


def _is_interactive() -> bool:
    """Split out from the call site so tests can force the prompt branch
    without fighting CliRunner's stdin substitution (sys.stdin is rebound to
    a fresh object per invoke(), so patching the pre-invoke object is a
    no-op)."""
    return sys.stdin.isatty()


def setup(
    ctx: typer.Context,
    pg_host: str = typer.Option(None, "--pg-host", help="Postgres host (QOD_PG_HOST)."),
    pg_port: str = typer.Option(None, "--pg-port", help="Postgres port (QOD_PG_PORT)."),
    pg_user: str = typer.Option(None, "--pg-user", help="Postgres user (QOD_PG_USER)."),
    pg_password: str = typer.Option(
        None, "--pg-password", help="Postgres password (QOD_PG_PASSWORD)."
    ),
    pg_dbname: str = typer.Option(
        None, "--pg-dbname", help="Control-plane database name (QOD_PG_DBNAME)."
    ),
    admin_username: str = typer.Option(
        None, "--admin-username", help="Seeded admin username (QOD_ADMIN_USERNAME)."
    ),
    auth: bool = typer.Option(
        None, "--auth/--no-auth",
        help="DB-backed auth (QOD_AUTH_DB_ENABLED). --no-auth needs another authenticator "
        "(a Keycloak or Azure password backend, or a bearer provider with system-scope "
        "OIDC sign-in), or the manager refuses to boot.",
    ),
    tls: bool = typer.Option(
        None, "--tls/--no-tls", help="FlightSQL edge TLS (PROXY_TLS_ENABLED)."
    ),
    set_: list[str] = typer.Option(
        [], "--set", metavar="KEY=VALUE", help="Any other QOD_*/PROXY_* var. Repeatable."
    ),
    unset: list[str] = typer.Option(
        [], "--unset", metavar="KEY", help="Remove a previously stored var. Repeatable."
    ),
    non_interactive: bool = typer.Option(
        False, "--non-interactive", "-y", help="Skip prompts; apply only the flags/--set given."
    ),
    show: bool = typer.Option(
        False, "--show", help="Print the stored config and exit; no writes, no prompts."
    ),
    skip_checks: bool = typer.Option(
        False,
        "--skip-checks",
        help="Skip the Postgres connection check before saving (e.g. when "
        "configuring for a host unreachable from here).",
    ),
):
    """Configure `qod start` once so you can run it bare afterwards.

    With no flags on a terminal, prompts for Postgres coordinates, the admin
    username, and the auth/TLS toggles (current stored value, else the
    built-in default, as the prompt default - blank
    input keeps it). Any flag, or --set/--unset, skips the prompts and
    applies just what you passed - pair with --non-interactive for scripted/
    CI setup. `qod start` picks this up automatically; nothing here is
    passed to `qod start` directly.
    """
    current = load_start_env()

    if show:
        if not current:
            typer.echo(f"no start config stored yet (file: {config_path()})")
            typer.echo("run: qod setup")
            return
        rows = [{"key": k, "value": _redact(k, current[k])} for k in sorted(current)]
        render(rows, ctx.obj.json_output)
        return

    admin_pw.migrate_stored_password(lambda line: typer.echo(line, err=True))
    current = load_start_env()

    named = {
        "QOD_PG_HOST": pg_host,
        "QOD_PG_PORT": pg_port,
        "QOD_PG_USER": pg_user,
        "QOD_PG_PASSWORD": pg_password,
        "QOD_PG_DBNAME": pg_dbname,
        "QOD_ADMIN_USERNAME": admin_username,
        "QOD_AUTH_DB_ENABLED": None if auth is None else str(auth).lower(),
        "PROXY_TLS_ENABLED": None if tls is None else str(tls).lower(),
    }
    any_named = any(v is not None for v in named.values())

    interactive = (
        not non_interactive
        and not any_named
        and not set_
        and not unset
        and _is_interactive()
    )

    # Checks first: a malformed --set and the nothing-to-do case are rejected
    # BEFORE any prompting or persistence, so the user never answers a prompt
    # (or writes a file) only to be told the invocation was invalid.
    set_values: dict[str, str] = {}
    for pair in set_:
        if "=" not in pair:
            raise typer.BadParameter(f"--set expects KEY=VALUE (got: {pair!r})")
        key, _, val = pair.partition("=")
        set_values[key.strip()] = val.strip()

    if admin_pw.KEY in set_values:
        typer.echo(
            f"error: {admin_pw.KEY} is no longer stored. The admin password is set at the "
            "first qod start / qod serve; change it with qod auth change-password, or "
            "recover it with qod admin reset-password.",
            err=True,
        )
        raise typer.Exit(1)

    if REMOVED_API_KEY in set_values:
        typer.echo(
            f"error: {REMOVED_API_KEY} was removed: there is no static API key any more, and "
            "qod start / qod serve refuse to launch with one. Use qod login, or a personal "
            "access token (qod auth pat create) in QOD_TOKEN.",
            err=True,
        )
        raise typer.Exit(1)

    if not interactive and not any_named and not set_values and not unset:
        typer.echo(
            "nothing to do: no flags, no --set/--unset, and not an interactive terminal.\n"
            "Use --show to inspect the stored config, or pass flags/--set (add "
            "--non-interactive to skip prompts in a script).",
            err=True,
        )
        raise typer.Exit(1)

    if interactive:
        typer.echo("Configuring qod start. Enter keeps the shown value.")
        for key, label, default in _PROMPTS:
            existing = current.get(key, default)
            hidden = key in _SECRET_KEYS
            named[key] = typer.prompt(
                label, default=existing, hide_input=hidden, show_default=not hidden
            )

    values = {k: v for k, v in named.items() if v is not None}
    values.update(set_values)

    # Connection check before saving, only when this invocation touches
    # connection-relevant keys (so e.g. `--set QOD_MIN_PORT=...` while the
    # database happens to be down is not blocked). Interactive runs always
    # collect them, so they always check.
    touches_connection = bool(
        _CONNECTION_KEYS.intersection(values) or _CONNECTION_KEYS.intersection(unset)
    )
    if touches_connection and not skip_checks:
        merged_preview = {**current, **values}
        for key in unset:
            merged_preview.pop(key, None)
        ok, detail = _check_connection(merged_preview)
        if ok:
            # stderr in json mode so machine-readable stdout stays pure JSON.
            typer.echo(detail, err=ctx.obj.json_output)
        elif interactive:
            typer.echo(f"connection check failed: {detail}", err=True)
            if not typer.confirm("save anyway?", default=False):
                typer.echo("nothing saved.", err=True)
                raise typer.Exit(1)
        else:
            typer.echo(
                f"connection check failed: {detail}\n"
                "nothing saved. Fix the coordinates, or pass --skip-checks to "
                "save without checking.",
                err=True,
            )
            raise typer.Exit(1)

    save_start_env(values, remove=unset)
    merged = {**current, **values}
    for key in unset:
        merged.pop(key, None)
    render(
        {
            "saved": len(values),
            "removed": len(unset),
            "total": len(merged),
            "file": str(config_path()),
        },
        ctx.obj.json_output,
    )
    if not ctx.obj.json_output:
        typer.echo(f"saved to {config_path()}")
        typer.echo("run: qod start")
    if values.get("QOD_AUTH_DB_ENABLED", "").strip().lower() == "false":
        typer.echo(_NO_DB_AUTH_NOTE, err=True)
