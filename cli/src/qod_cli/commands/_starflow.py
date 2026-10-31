"""Shared `--with-starflow` options and glue for `qod start` and `qod serve`.

The `--starflow-*` env fallbacks are read here, not bound as typer envvars, so
an exported STARLAKE_HOME never trips the "needs --with-starflow" check and the
port/URL precedence stays flag > env file > process env > default."""

from __future__ import annotations

import os
import sys
import threading
from dataclasses import dataclass
from pathlib import Path

import httpx
import typer

from .. import launcher, starflow
from ..config import Settings, config_path, save_start_env
from ..rest import RestClient

WITH_STARFLOW = typer.Option(
    False,
    "--with-starflow",
    envvar="QOD_WITH_STARFLOW",
    help="Also run Starflow (the Starlake API + UI) next to the manager, sharing its "
    "Postgres server and SSO. Installs Starflow on first use.",
)
STARFLOW_HOME = typer.Option(
    None,
    "--starflow-home",
    help="Starflow install dir (default: STARLAKE_HOME, then ~/starlake when installed, "
    "then the qod cache). Installed into when empty.",
)
STARFLOW_VERSION = typer.Option(
    None,
    "--starflow-version",
    help="Starflow release to install when none is found (default: STARFLOW_VERSION, "
    "then the latest release).",
)
STARFLOW_PORT = typer.Option(
    None, "--starflow-port", help="Starflow HTTP port (default: SL_API_HTTP_PORT, then 9900)."
)
STARFLOW_URL = typer.Option(
    None,
    "--starflow-url",
    help="Browser-facing Starflow URL, for clients on other machines (default: SL_URL, "
    "then http://localhost:<port>).",
)
STARFLOW_ENV_FILE = typer.Option(
    None,
    "--starflow-env-file",
    help="KEY=VALUE file applied to Starflow only (default: STARFLOW_ENV_FILE). Values "
    "qod injects to pair Starflow with the manager override it.",
)


@dataclass(frozen=True)
class StarflowRequest:
    home: str | None
    version: str | None
    port: int | None
    url: str | None
    env_file: Path | None


@dataclass(frozen=True)
class StarflowPlan:
    home: Path
    port: int
    url: str
    secret: str
    file_env: dict
    java_home: str


def _is_windows() -> bool:
    """Seam: tests flip this instead of the process-global sys.platform."""
    return sys.platform == "win32"


def _user_home() -> Path:
    """Seam: tests point this away from the developer's real ~/starlake."""
    return Path.home()


def request(with_starflow, home, version, port, url, env_file) -> StarflowRequest | None:
    flags = {
        "--starflow-home": home,
        "--starflow-version": version,
        "--starflow-port": port,
        "--starflow-url": url,
        "--starflow-env-file": env_file,
    }
    given = [name for name, value in flags.items() if value is not None]
    if not with_starflow:
        if given:
            typer.echo(f"error: {', '.join(given)} requires --with-starflow", err=True)
            raise typer.Exit(2)
        return None
    if _is_windows():
        typer.echo("error: --with-starflow is not supported on Windows yet", err=True)
        raise typer.Exit(1)
    return StarflowRequest(home=home, version=version, port=port, url=url, env_file=env_file)


def prepare(req: StarflowRequest, base_env: dict, java: str) -> StarflowPlan:
    """Everything that can fail before the manager boots: env file, port/URL,
    install, the shared session secret (persisted before boot so a restart keeps SSO)."""
    echo = lambda line: typer.echo(line, err=True)
    proc_env = dict(os.environ)
    raw_file = req.env_file or base_env.get("STARFLOW_ENV_FILE")
    try:
        file_env = starflow.parse_env_file(Path(raw_file).expanduser()) if raw_file else {}
        port, url = starflow.resolve_port_and_url(req.port, req.url, file_env, proc_env)
        home = starflow.resolve_home(
            req.home or base_env.get("STARLAKE_HOME"), launcher.default_cache_dir(), _user_home()
        )
        java_home = starflow.java_home_of(java)
        _reap_leftover(echo)
        starflow.ensure_installed(
            home,
            req.version or base_env.get("STARFLOW_VERSION"),
            java_home,
            echo=echo,
        )
    except (starflow.StarflowError, OSError, httpx.HTTPError) as exc:
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
    generated = not base_env.get("QOD_SESSION_JWT_SECRET")
    secret = starflow.ensure_shared_secrets(base_env, save_start_env)
    if generated:
        # The name only: the value never reaches the terminal.
        echo(
            "generated QOD_SESSION_JWT_SECRET for the Starflow pairing "
            f"(stored in {config_path()})"
        )
    return StarflowPlan(
        home=home,
        port=port,
        url=url,
        secret=secret,
        file_env=file_env,
        java_home=java_home,
    )


def _reap_leftover(echo) -> None:
    """A Starflow left running by a manager that died on its own would lose its
    pid file to this run. No-op without a pid file; never signals a group that
    is not provably ours; a failure is a warning, never a reason not to start."""
    try:
        starflow.stop_running(launcher.default_data_dir(), echo=echo)
    except Exception as exc:
        echo(f"WARN: could not stop the previous Starflow: {exc}")


def start_after_ready(
    plan: StarflowPlan, *, mgr_env: dict, pg: starflow.PgCoords, ready_timeout: float = 180.0
) -> None:
    mgr_port = mgr_env.get("QOD_ON_DEMAND_PORT") or "20900"
    env = starflow.starflow_env(
        dict(os.environ),
        plan.file_env,
        mgr_env,
        port=plan.port,
        url=plan.url,
        secret=plan.secret,
        pg=pg,
        java_home=plan.java_home,
    )
    _spawn(
        client=RestClient(Settings(manager_url=f"http://localhost:{mgr_port}")),
        pg=pg,
        home=plan.home,
        env=env,
        state_dir=launcher.default_data_dir(),
        url=plan.url,
        ready_timeout=ready_timeout,
        echo=lambda line: typer.echo(line, err=True),
    )


def _spawn(**kwargs) -> None:
    """Seam: tests replace this so no thread polls a manager that never boots."""
    threading.Thread(target=starflow.run_after_ready, kwargs=kwargs, daemon=True).start()
