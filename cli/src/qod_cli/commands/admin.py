"""`qod admin`: superuser recovery and the security audit.

`qod admin security-audit` calls the manager's REST API (superuser only) and
prints how the deployment stands against the docs' production checklists.

`qod admin reset-password`: break-glass recovery of a superuser password.

Runs the manager jar's one-shot `admin reset-password` subcommand, which
writes the new bcrypt hash straight into the control-plane database (lockout
cleared), so it works with every login path blocked. A disabled superuser is
re-enabled only when `--username` names it: re-enabling one also revives its
personal access tokens, so resetting the configured admin names never undoes a
deliberate disable. It never starts Postgres:
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
from typing import List

import typer

from .. import admin_password, launcher
from ..config import load_start_env
from ._launch import resolve_jar, resolve_java
from ..output import render
from ._run import call
from .status import _embedded_postgres_port, _tcp_open

app = typer.Typer(help="Superuser recovery and the security audit.", no_args_is_help=True)


@app.command("reset-password")
def reset_password(
    username: str = typer.Option(
        None,
        "--username",
        help="Superuser to reset (default: every name in QOD_ADMIN_USERNAME, "
        "seeded as one credential). A disabled superuser named here is re-enabled.",
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

    Also clears its lockout. A disabled superuser is re-enabled only when
    --username names it, which also revives its personal access tokens;
    without --username a disabled admin gets its new password but stays
    disabled.
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
    # --reenable only for an explicit --username: the configured names never revive a
    # superuser someone disabled on purpose.
    args = (
        ["admin", "reset-password", *users]
        + (["--reenable"] if username else [])
        + (["--must-change"] if must_change else [])
    )
    cmd = launcher.build_jar_command(
        resolve_java(), str(jar_path), args, java_opts=env.get("JAVA_OPTS")
    )
    proc = subprocess.run(cmd, input=password + "\n", text=True, env=env)
    raise typer.Exit(proc.returncode)


_ORDER = ("critical", "warn", "error", "ok", "na")
_LABEL = {"critical": "critical", "warn": "warning", "error": "error", "ok": "ok", "na": "n/a"}
_PLURAL = {"critical": "critical", "warn": "warnings", "error": "errors", "ok": "ok", "na": "n/a"}


def _summary(s: dict) -> str:
    parts = [f"{s[k]} {_LABEL[k] if s[k] == 1 else _PLURAL[k]}" for k in _ORDER if s.get(k)]
    return ", ".join(parts)


def _unexpected(missing: str) -> typer.Exit:
    typer.echo(f"error: unexpected answer from /api/security/audit (missing {missing})", err=True)
    return typer.Exit(1)


@app.command("security-audit")
def security_audit(
    ctx: typer.Context,
    fail_on: List[str] = typer.Option(
        [],
        "--fail-on",
        help="Also fail (exit 3) on warn and/or error findings. Repeatable, or comma-separated.",
    ),
):
    """How this deployment stands against the docs' Secure in production checklists (superuser).

    Exits 3 when a critical finding (or a --fail-on status) is present."""
    bad = {p.strip().lower() for f in fail_on for p in f.split(",") if p.strip()}
    unknown = bad - {"warn", "error"}
    if unknown:
        typer.echo(f"error: --fail-on takes warn or error, not {', '.join(sorted(unknown))}", err=True)
        raise typer.Exit(1)
    data = call(ctx, "GET", "/api/security/audit", quiet=True)
    if not isinstance(data, dict):
        raise _unexpected("an object")
    if not isinstance(data.get("findings"), list):
        raise _unexpected("findings")
    if ctx.obj.json_output:
        render(data, True)
    else:
        if not isinstance(data.get("summary"), dict):
            raise _unexpected("summary")
        line = _summary(data["summary"]) or "no findings"
        typer.echo(f"{line}  ({data.get('replica', '?')}, {data.get('generatedAt', '?')})")
        passing = 0
        for f in data["findings"]:
            status = f.get("status")
            if status in ("ok", "na"):
                passing += 1
                continue
            typer.echo(f"\n[{_LABEL.get(status, status)}] {f.get('title', '')}  ({f.get('id', '')})")
            typer.echo(f"    {f.get('detail', '')}")
            if f.get("fix"):
                typer.echo(f"    fix: {f['fix']}")
            if f.get("docs"):
                typer.echo(f"    docs: {f['docs']}")
        if passing:
            typer.echo(f"\n{passing} checks passed or do not apply (--json lists them).")
    statuses = {f.get("status") for f in data["findings"]}
    if "critical" in statuses or statuses & bad:
        raise typer.Exit(3)
