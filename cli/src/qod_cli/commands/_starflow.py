"""Shared `--with-starflow` options and glue for `qod start` and `qod serve`.

The `--starflow-*` env fallbacks are read here, not bound as typer envvars, so
an exported STARLAKE_HOME never trips the "needs --with-starflow" check and the
port/URL precedence stays flag > env file > process env > default."""

from __future__ import annotations

import os
import secrets
import signal
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
    "Postgres server (with qod serve --demo: H2 in a folder deleted on exit) and SSO. "
    "Installs Starflow on first use.",
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
    # The demo's folder for Starflow's state, deleted on exit; None outside the demo.
    run_dir: Path | None = None


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


def _resolve(req: StarflowRequest, base_env: dict, java: str) -> tuple[Path, int, str, dict, str]:
    """What both paths settle before the manager boots: env file, port/URL, the leftover Starflow,
    install. Returns (home, port, url, file_env, java_home)."""
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
    return home, port, url, file_env, java_home


def prepare(req: StarflowRequest, base_env: dict, java: str) -> StarflowPlan:
    """Everything that can fail before the manager boots: env file, port/URL,
    install, the shared session secret (persisted before boot so a restart keeps SSO)."""
    home, port, url, file_env, java_home = _resolve(req, base_env, java)
    generated = not base_env.get("QOD_SESSION_JWT_SECRET")
    secret = starflow.ensure_shared_secrets(base_env, save_start_env)
    if generated:
        # The name only: the value never reaches the terminal.
        typer.echo(
            "generated QOD_SESSION_JWT_SECRET for the Starflow pairing "
            f"(stored in {config_path()})",
            err=True,
        )
    return StarflowPlan(
        home=home,
        port=port,
        url=url,
        secret=secret,
        file_env=file_env,
        java_home=java_home,
    )


def demo_run_dir() -> Path:
    return launcher.default_data_dir() / starflow.DEMO_RUN_DIR


def prepare_demo(
    req: StarflowRequest, base_env: dict, java: str, token=secrets.token_urlsafe
) -> StarflowPlan:
    """`prepare` for the demo: a fresh secret per run, never stored (the demo keeps nothing, and a
    stored secret would also reach a later plain serve), and an empty run folder, made after
    `_resolve` reaped the leftover Starflow so that nothing still writes there."""
    home, port, url, file_env, java_home = _resolve(req, base_env, java)
    run_dir = demo_run_dir()
    try:
        starflow.reset_run_dir(run_dir)
    except OSError as exc:
        typer.echo(f"error: could not prepare {run_dir}: {exc}", err=True)
        raise typer.Exit(1)
    return StarflowPlan(
        home=home,
        port=port,
        url=url,
        secret=token(48),
        file_env=file_env,
        java_home=java_home,
        run_dir=run_dir,
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


def start_demo_after_ready(
    plan: StarflowPlan, *, mgr_env: dict, loopback_only: bool, ready_timeout: float = 180.0
) -> None:
    """`start_after_ready` for the demo: no Postgres database, Starflow's state in the run folder,
    the wait and the manager on 127.0.0.1."""
    mgr_port = mgr_env.get("QOD_ON_DEMAND_PORT") or "20900"
    env = starflow.starflow_env(
        dict(os.environ),
        plan.file_env,
        mgr_env,
        port=plan.port,
        url=plan.url,
        secret=plan.secret,
        pg=None,
        java_home=plan.java_home,
    )
    env = starflow.demo_starflow_env(
        env, run_dir=plan.run_dir, mgr_port=mgr_port, loopback_only=loopback_only
    )
    _spawn(
        client=RestClient(Settings(manager_url=f"http://127.0.0.1:{mgr_port}")),
        pg=None,
        home=plan.home,
        env=env,
        state_dir=launcher.default_data_dir(),
        url=plan.url,
        ready_timeout=ready_timeout,
        echo=lambda line: typer.echo(line, err=True),
        ensure_db=lambda pg: False,
        note=starflow.DEMO_SIGN_IN_NOTE,
    )


def _killpg(pgid: int, sig: int) -> None:
    """Seam: tests replace this. Resolved at call time: os.killpg does not exist on Windows, where
    this module must still import (the demo Starflow is refused there before any of this runs)."""
    os.killpg(pgid, sig)


def finish_demo(plan: StarflowPlan, echo=lambda line: typer.echo(line, err=True)) -> None:
    """Once the demo jar is gone: stop a Starflow still running (the jar exited on its own, or an
    interrupt landed as Starflow launched), then delete the run folder. Never raises an error: a
    failure is a WARN and leaves the exit code alone. A second interrupt during the bounded wait
    kills the Starflow group at once, as a second Ctrl-C kills the manager, and the folder is still
    deleted."""
    state_dir = launcher.default_data_dir()
    pgid = None
    try:
        pgid = starflow.begin_stop(state_dir, echo=echo)
        if pgid is not None:
            starflow.finish_stop(state_dir, pgid, echo=echo)
    except KeyboardInterrupt:
        echo("second interrupt; killing Starflow directly.")
        if pgid is not None:
            try:
                _killpg(pgid, signal.SIGKILL)
            except Exception:
                pass  # already gone
    except Exception as exc:
        echo(f"WARN: could not stop Starflow: {exc}")
    try:
        starflow.remove_run_dir(plan.run_dir)
    except Exception as exc:
        echo(f"WARN: could not delete {plan.run_dir}: {exc}; remove it by hand")


def _spawn(**kwargs) -> None:
    """Seam: tests replace this so no thread polls a manager that never boots."""
    threading.Thread(target=starflow.run_after_ready, kwargs=kwargs, daemon=True).start()
