"""`qod setup`: persist the env vars `qod start` reads, so you don't have to
`export` them (or re-type flags) every time you run `uvx qod start`.

Storage: the same TOML file `qod auth login` writes CLI connection profiles
to (QOD_CONFIG_FILE overrides the path), under a separate `[start]` table -
see qod_cli.config.load_start_env/save_start_env. Precedence at `qod start`
time is file < real process env, so a shell `export QOD_PG_HOST=...` (or a
docker-compose-style `.env` sourced into the shell) still wins over this.
"""

from __future__ import annotations

import sys

import typer

from ..config import config_path, load_start_env, save_start_env
from ..output import render

# (env var, CLI flag label, prompt text, default) - the knobs `qod start`
# actually reads today (_pg_coords in commands/start.py, plus the
# QOD_ADMIN_*/QOD_API_KEY/QOD_AUTH_DB_ENABLED/PROXY_TLS_ENABLED vars that
# application.conf honors - see CLAUDE.md "Every scalar ... accepts a QOD_*
# env-var override"). --set covers anything not on this short list.
_PROMPTS: tuple[tuple[str, str, str], ...] = (
    ("QOD_PG_HOST", "Postgres host", "localhost"),
    ("QOD_PG_PORT", "Postgres port", "5432"),
    ("QOD_PG_USER", "Postgres user", "postgres"),
    ("QOD_PG_PASSWORD", "Postgres password", "azizam"),
    ("QOD_PG_DBNAME", "Control-plane database", "qod"),
    ("QOD_ADMIN_USERNAME", "Admin username", "admin@localhost.local,admin"),
    ("QOD_ADMIN_PASSWORD", "Admin password", "admin"),
    ("QOD_API_KEY", "Static REST API key (blank = none)", ""),
    ("QOD_AUTH_DB_ENABLED", "Enable DB-backed auth (true/false)", "true"),
    ("PROXY_TLS_ENABLED", "Enable FlightSQL edge TLS (true/false)", "true"),
)
_SECRET_KEYS = {"QOD_PG_PASSWORD", "QOD_ADMIN_PASSWORD", "QOD_API_KEY"}


def _redact(key: str, value: str) -> str:
    if key not in _SECRET_KEYS or not value:
        return value
    return "*" * min(len(value), 8)


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
    admin_password: str = typer.Option(
        None, "--admin-password", help="Seeded admin password (QOD_ADMIN_PASSWORD)."
    ),
    api_key: str = typer.Option(
        None, "--api-key", help="Static REST API key; blank disables it (QOD_API_KEY)."
    ),
    auth: bool = typer.Option(
        None, "--auth/--no-auth", help="DB-backed auth (QOD_AUTH_DB_ENABLED)."
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
):
    """Configure `qod start` once so you can run it bare afterwards.

    With no flags on a terminal, prompts for Postgres coordinates, admin
    credentials, the static API key, and the auth/TLS toggles (current
    stored value, else the built-in default, as the prompt default - blank
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

    named = {
        "QOD_PG_HOST": pg_host,
        "QOD_PG_PORT": pg_port,
        "QOD_PG_USER": pg_user,
        "QOD_PG_PASSWORD": pg_password,
        "QOD_PG_DBNAME": pg_dbname,
        "QOD_ADMIN_USERNAME": admin_username,
        "QOD_ADMIN_PASSWORD": admin_password,
        "QOD_API_KEY": api_key,
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
