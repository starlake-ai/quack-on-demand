"""`qod admin reset-password`: break-glass recovery of a superuser password.

Runs the manager jar's one-shot `admin reset-password` subcommand, which
writes the new bcrypt hash straight into the control-plane database (lockout
cleared, account re-enabled), so it works with every login path blocked. It
never starts Postgres:
an external control plane is reached as it is (the manager may be down), and
the `qod serve` embedded one only while its Postgres is running.
Authority is reaching that database with the configured credentials; anyone
with that access could already rewrite the row by hand. The password travels
on the child's stdin, never in argv or a file.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import typer

from .. import admin_password, launcher
from ..config import load_start_env
from ._launch import resolve_jar, resolve_java
from .status import _embedded_postgres_port, _tcp_open

app = typer.Typer(help="Superuser recovery (never starts Postgres).", no_args_is_help=True)


@app.command("reset-password")
def reset_password(
    username: str = typer.Option(
        None,
        "--username",
        help="Superuser to reset (default: every name in QOD_ADMIN_USERNAME, "
        "seeded as one credential).",
    ),
    must_change: bool = typer.Option(
        False, "--must-change", help="Force a password change at the next login."
    ),
    embedded: bool = typer.Option(
        None,
        "--embedded/--external",
        help="Target the qod serve embedded control plane (its Postgres must be running), "
        "or the external Postgres (default: embedded when its Postgres is running and "
        "QOD_PG_HOST is unset).",
    ),
    version: str = typer.Option(None, "--version", envvar="QOD_VERSION", help="Manager release."),
    jar: Path = typer.Option(None, "--jar", help="Run this local jar instead of downloading."),
):
    """Set a new password for a superuser directly in the control-plane database.

    Also clears its lockout and re-enables the account.
    """
    base_env = {**load_start_env(), **os.environ}
    users = [username] if username else admin_password.admin_usernames(base_env)
    embedded_dir = Path(
        base_env.get("QOD_PG_EMBEDDED_DATA_DIR") or (launcher.default_data_dir() / "pg")
    )
    pgdata = embedded_dir / "pgdata"
    has_pgdata = pgdata.is_dir()
    # Same probe as `qod status`: the port from postmaster.pid, then a TCP connect (never a
    # signal, which terminates the process on Windows). A missing pid file means stopped.
    embedded_running = (
        has_pgdata
        and (pgdata / "postmaster.pid").is_file()
        and _tcp_open("localhost", _embedded_postgres_port(pgdata))
    )
    use_embedded = (
        embedded
        if embedded is not None
        else (embedded_running and not base_env.get("QOD_PG_HOST"))
    )
    if use_embedded and not has_pgdata:
        typer.echo(
            f"error: nothing to reset; run qod serve first (no control plane at {embedded_dir})",
            err=True,
        )
        raise typer.Exit(1)
    if use_embedded and not embedded_running:
        typer.echo(
            f"error: the embedded control plane at {embedded_dir} is not running; start it with "
            "qod serve, then retry (qod admin never starts Postgres)",
            err=True,
        )
        raise typer.Exit(1)

    if admin_password.is_interactive():
        password = admin_password.prompt_new_password("New password")
    else:
        password = sys.stdin.readline().rstrip("\r\n")
    if not password:
        typer.echo("error: empty password", err=True)
        raise typer.Exit(1)

    env = dict(base_env)
    if use_embedded:
        env["QOD_PG_EMBEDDED"] = "true"
        env["QOD_PG_EMBEDDED_DATA_DIR"] = str(embedded_dir)
        typer.echo(f"target: embedded control plane at {embedded_dir}")
    else:
        # An inherited QOD_PG_EMBEDDED=true must not redirect --external.
        env["QOD_PG_EMBEDDED"] = "false"
        typer.echo(
            f"target: Postgres {env.get('QOD_PG_HOST') or 'localhost'}:"
            f"{env.get('QOD_PG_PORT') or '5432'}/{env.get('QOD_PG_DBNAME') or 'qod'}"
        )
    jar_path = jar.resolve() if jar is not None else resolve_jar(version)
    args = ["admin", "reset-password", *users] + (["--must-change"] if must_change else [])
    cmd = launcher.build_jar_command(
        resolve_java(), str(jar_path), args, java_opts=env.get("JAVA_OPTS")
    )
    proc = subprocess.run(cmd, input=password + "\n", text=True, env=env)
    raise typer.Exit(proc.returncode)
