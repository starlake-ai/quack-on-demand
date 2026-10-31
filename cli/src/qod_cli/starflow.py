"""`--with-starflow`: run Starflow (the Starlake API + UI) beside the manager.

Logic only (no typer): env and URL resolution, the shared session secret, the
installer, the Starflow database on QoD's Postgres server, and the process
launch and teardown. The command glue lives in commands/_starflow.py. Design:
docs/superpowers/specs/2026-10-04-with-starflow-design.md.
"""

from __future__ import annotations

import os
import secrets
import signal
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

import httpx

from .config import REMOVED_API_KEY
from .serve_provision import wait_ready

DEFAULT_PORT = 9900
STARFLOW_DB = "starlake"
# Relative to a Starflow home; present once `starlake install` put the API in place.
RUN_API = Path("bin") / "api" / "bin" / "local-run-api"


class StarflowError(Exception):
    """A Starflow pre-flight or install failure, reported without a traceback."""


@dataclass(frozen=True)
class PgCoords:
    host: str
    port: int
    user: str
    password: str
    admin_db: str = "postgres"


def parse_env_file(path: Path) -> dict[str, str]:
    """KEY=VALUE lines; `#` comments, blank lines, an `export ` prefix and one
    pair of surrounding quotes are tolerated. No interpolation."""
    out: dict[str, str] = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].lstrip()
        key, sep, value = line.partition("=")
        if not sep:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        out[key.strip()] = value
    return out


def is_installed(home: Path) -> bool:
    return (home / RUN_API).is_file()


def resolve_home(explicit: str | None, cache_dir: Path, user_home: Path) -> Path:
    """Explicit dir, else an installed ~/starlake (the official installer's
    default), else the qod cache."""
    if explicit:
        return Path(explicit).expanduser()
    legacy = user_home / "starlake"
    if is_installed(legacy):
        return legacy
    return cache_dir / "starflow"


def _first(*values):
    return next((v for v in values if v not in (None, "")), None)


def resolve_port_and_url(
    flag_port: int | None, flag_url: str | None, file_env: dict, proc_env: dict
) -> tuple[int, str]:
    raw_port = _first(
        flag_port, file_env.get("SL_API_HTTP_PORT"), proc_env.get("SL_API_HTTP_PORT"), DEFAULT_PORT
    )
    try:
        port = int(raw_port)
    except (TypeError, ValueError):
        raise StarflowError(f"Starflow port is not a number: {raw_port}")
    url = _first(flag_url, file_env.get("SL_URL"), proc_env.get("SL_URL"))
    url = (url or f"http://localhost:{port}").rstrip("/")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise StarflowError(f"Starflow URL must be http(s)://host[:port]: {url}")
    return port, url


def ensure_shared_secrets(
    base_env: dict,
    save: Callable[[dict], None],
    token: Callable[[int], str] = secrets.token_urlsafe,
) -> str:
    """The session secret both sides share: the manager signs its sessions with it
    and Starflow mints its SSO tokens with it. Generated once and persisted (qod
    config [start] table) so SSO survives a restart. Nothing else is shared: no
    static API key is part of the pairing."""
    secret = base_env.get("QOD_SESSION_JWT_SECRET") or ""
    if not secret:
        secret = token(48)
        save({"QOD_SESSION_JWT_SECRET": secret})
    return secret


def manager_env(url: str, secret: str) -> dict[str, str]:
    """Overlay for the manager JVM: Starlake menu/SSO on, the shared secret.
    JWT_SECRET_KEY feeds the edge's bearer provider, which must verify the
    tokens Starflow mints with the same secret."""
    return {
        "SL_ENABLED": "true",
        "SL_URL": url,
        "QOD_SESSION_JWT_SECRET": secret,
        "JWT_SECRET_KEY": secret,
    }


def starflow_env(
    proc_env: dict,
    file_env: dict,
    mgr_env: dict,
    *,
    port: int,
    url: str,
    secret: str,
    pg: PgCoords,
    java_home: str,
) -> dict[str, str]:
    """Starflow's process env: process env (minus the manager's JAVA_OPTS), then
    the env file, then the coupling vars, which win so a stale value in the
    file cannot break the pairing with the manager just launched. QOD_API_KEY is
    dropped from both sources: no static key is part of the pairing, and an older
    Starflow holding one sends it instead of falling back to the user's session."""
    mgr_port = mgr_env.get("QOD_ON_DEMAND_PORT") or "20900"
    flight_port = mgr_env.get("PROXY_PORT") or "31338"
    tls = (mgr_env.get("PROXY_TLS_ENABLED") or "true").lower() != "false"
    public = (mgr_env.get("QOD_PUBLIC_BASE_URL") or f"http://localhost:{mgr_port}").rstrip("/")
    env = {k: v for k, v in proc_env.items() if k not in ("JAVA_OPTS", REMOVED_API_KEY)}
    env.update({k: v for k, v in file_env.items() if k != REMOVED_API_KEY})
    # local-run-api defaults this to http://localhost:9900/airflow/; follow the
    # Starflow URL instead, unless the user set it.
    env.setdefault("SL_API_ORCHESTRATOR_URL", f"{url}/airflow/")
    env.update(
        {
            "QOD_ENABLED": "true",
            # Server-to-server on the same host: loopback.
            "QOD_URL": f"http://localhost:{mgr_port}",
            "QOD_FLIGHT_URL": f"{'https' if tls else 'http'}://localhost:{flight_port}",
            # The edge's certificate is self-signed.
            "QOD_TLS_INSECURE": "true",
            # Browser-facing: must work from other machines.
            "QOD_UI_URL": f"{public}/ui",
            "JWT_SECRET_KEY": secret,
            "SL_API_HTTP_PORT": str(port),
            "SL_API_HTTP_FRONT_URL": url,
            "SL_API_DOMAIN": urlparse(url).hostname or "localhost",
            "SL_API_JDBC_URL": f"jdbc:postgresql://{pg.host}:{pg.port}/{STARFLOW_DB}",
            "SL_API_JDBC_USER": pg.user,
            "SL_API_JDBC_PASSWORD": pg.password,
            "SL_API_JDBC_DRIVER": "org.postgresql.Driver",
            "JAVA_HOME": java_home,
        }
    )
    return env


# The official installer (starflow/distrib/setup.sh) fetches this script, then
# runs `SL_VERSION=<v> <dir>/starlake install`, which drives setup.jar without
# prompts. We do the same minus setup.sh's prompts and shell-rc edits.
STARLAKE_SH_URL = "https://raw.githubusercontent.com/starlake-ai/starflow/master/distrib/starlake.sh"
RELEASES_LATEST_URL = "https://api.github.com/repos/starlake-ai/starflow/releases/latest"


def latest_version(get=httpx.get) -> str:
    resp = get(RELEASES_LATEST_URL, timeout=15.0, follow_redirects=True)
    resp.raise_for_status()
    tag = resp.json()["tag_name"]
    return tag[1:] if tag.startswith("v") else tag


def java_home_of(java: str, run=subprocess.run) -> str:
    """JAVA_HOME of the Java qod resolved. Asked of the JVM itself because a
    PATH java is often a shim (/usr/bin/java on macOS) whose parent is not a
    Java home."""
    proc = run([java, "-XshowSettings:properties", "-version"], capture_output=True, text=True)
    for line in (proc.stderr or "").splitlines():
        key, sep, value = line.strip().partition("=")
        if sep and key.strip() == "java.home":
            return value.strip()
    return str(Path(os.path.realpath(java)).parent.parent)


def _download(url: str, dest: Path) -> None:
    with httpx.stream("GET", url, timeout=60.0, follow_redirects=True) as resp:
        resp.raise_for_status()
        with dest.open("wb") as out:
            for chunk in resp.iter_bytes():
                out.write(chunk)


def install(
    home: Path,
    version: str,
    java_home: str,
    download: Callable[[str, Path], None] | None = None,
    run=subprocess.run,
) -> None:
    home.mkdir(parents=True, exist_ok=True)
    script = home / "starlake"
    (download or _download)(STARLAKE_SH_URL, script)
    script.chmod(0o755)
    env = {**os.environ, "SL_VERSION": version, "JAVA_HOME": java_home}
    # Output streams to the terminal: a long, announced download.
    proc = run([str(script), "install"], cwd=home, env=env)
    if proc.returncode != 0:
        raise StarflowError(f"Starflow install into {home} failed (exit {proc.returncode})")
    if not is_installed(home):
        raise StarflowError(f"Starflow install into {home} finished but {RUN_API} is missing")


def _refuse_foreign_dir(home: Path) -> None:
    """Install only into a missing or empty dir, or one holding our own
    `starlake` script (an interrupted earlier install, which must stay
    resumable); never into an unrelated non-empty directory."""
    if not home.is_dir() or (home / "starlake").is_file():
        return
    # Finder drops a .DS_Store into any folder it opens: that folder is still "empty".
    if any(entry.name != ".DS_Store" for entry in home.iterdir()):
        raise StarflowError(
            f"{home} is not a Starflow install and is not empty; install Starflow there "
            "yourself or pick an empty directory (--starflow-home or STARLAKE_HOME)"
        )


def ensure_installed(
    home: Path,
    version: str | None,
    java_home: str,
    echo: Callable[[str], None],
    latest=latest_version,
    installer=install,
) -> None:
    if is_installed(home):
        return
    _refuse_foreign_dir(home)
    chosen = version or latest()
    echo(
        f"installing Starflow {chosen} into {home} (first run only: Starlake core, "
        "Spark and connector jars, several hundred MB)..."
    )
    installer(home, chosen, java_home)


PID_FILE = "starflow.pid"


def log_path(state_dir: Path) -> Path:
    return state_dir / "logs" / "starflow.log"


def ensure_database(pg: PgCoords, name: str = STARFLOW_DB, connect=None) -> bool:
    """Idempotent CREATE DATABASE next to the qod control plane. Returns True
    when it created the database."""
    if connect is None:
        import pg8000.native

        connect = pg8000.native.Connection
    conn = connect(
        user=pg.user, password=pg.password, host=pg.host, port=pg.port, database=pg.admin_db
    )
    try:
        if conn.run("SELECT 1 FROM pg_database WHERE datname = :n", n=name):
            return False
        conn.run(f'CREATE DATABASE "{name}"')
        return True
    finally:
        conn.close()


def launch(home: Path, env: dict, state_dir: Path, popen=subprocess.Popen):
    """Start Starflow detached in its own session (so teardown can signal the
    whole group), output to the log file. The pid file holds the group id and
    the home: `qod stop` signals the group only while a member still runs from
    that home."""
    home = Path(os.path.abspath(home))
    # `starlake serve` does the same chmod; an unzipped install may lack +x.
    for pattern in ("bin/api/bin/*", "bin/api/git/*.sh"):
        for f in home.glob(pattern):
            if f.is_file():
                f.chmod(f.stat().st_mode | 0o111)
    log = log_path(state_dir)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("ab") as out:
        proc = popen(
            [str(home / RUN_API), str(home)],
            cwd=home,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=out,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    (state_dir / PID_FILE).write_text(f"{proc.pid}\n{home}\n")
    return proc


def group_commands(pgid: int, run=subprocess.run) -> list[str]:
    """Command lines of every process in group `pgid` (macOS and Linux `ps`).
    A failing `ps` yields [], which callers read as "not provably ours"."""
    try:
        res = run(["ps", "-A", "-o", "pgid=,command="], capture_output=True, text=True)
    except OSError:
        return []
    if res.returncode != 0:
        return []
    cmds = []
    for line in res.stdout.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) != 2:
            continue
        try:
            group = int(parts[0])
        except ValueError:
            continue
        if group == pgid:
            cmds.append(parts[1])
    return cmds


def _read_pid_file(pid_file: Path) -> tuple[int, str]:
    """(pid, home); (0, "") when unparseable or in the old one-line format."""
    lines = pid_file.read_text().splitlines()
    try:
        pid = int(lines[0].strip())
    except (IndexError, ValueError):
        return 0, ""
    home = lines[1].strip() if len(lines) > 1 else ""
    return (pid, home) if home else (0, "")


def _group_alive(pgid: int, killpg) -> bool:
    try:
        killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False


def begin_stop(
    state_dir: Path,
    echo: Callable[[str], None],
    killpg=None,
    commands=group_commands,
) -> int | None:
    """First half of the teardown: SIGTERM the Starflow process group and return
    its id, or None when there is nothing of ours to stop. The group is
    signalled only when one of its members still runs from the recorded home
    (the leader shell may be gone while the JVM lives on); a stale pid file
    (gone, reused, old format, garbage) is removed without signalling. When it
    signalled, the pid file stays until finish_stop. `killpg` defaults to
    os.killpg, resolved only when signalling: the attribute does not exist on
    Windows, where this module must still import and a pid-file-less `qod stop`
    must still work."""
    pid_file = state_dir / PID_FILE
    try:
        pid, home = _read_pid_file(pid_file)
    except FileNotFoundError:
        return None
    except OSError:
        pid, home = 0, ""
    if not (pid > 0 and any(home in cmd for cmd in commands(pid))):
        pid_file.unlink(missing_ok=True)
        return None
    killpg = killpg or os.killpg
    echo(f"stopping Starflow (process group {pid})...")
    try:
        killpg(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    return pid


def finish_stop(
    state_dir: Path,
    pgid: int,
    echo: Callable[[str], None],
    killpg=None,
    sleep=time.sleep,
    timeout_s: float = 15.0,
    commands=group_commands,
) -> None:
    """Second half: bounded wait for the group begin_stop signalled, SIGKILL on
    timeout. Before the SIGKILL, ownership is checked again against the home in
    the pid file (begin_stop kept it): the group id may have been reused during
    the wait, and a group no member of which runs from that home is not ours.
    The pid file is removed even when the wait is interrupted."""
    try:
        killpg = killpg or os.killpg
        polls = max(1, int(timeout_s / 0.5))
        for _ in range(polls):
            if not _group_alive(pgid, killpg):
                break
            sleep(0.5)
        else:
            if _still_ours(state_dir, pgid, commands):
                echo(f"Starflow still running after {timeout_s:.0f}s; sending SIGKILL.")
                try:
                    killpg(pgid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            else:
                echo(f"Process group {pgid} no longer runs Starflow; not sending SIGKILL.")
    finally:
        (state_dir / PID_FILE).unlink(missing_ok=True)


def _still_ours(state_dir: Path, pgid: int, commands) -> bool:
    try:
        _, home = _read_pid_file(state_dir / PID_FILE)
    except OSError:
        return False
    return bool(home) and any(home in cmd for cmd in commands(pgid))


def stop_running(
    state_dir: Path,
    echo: Callable[[str], None],
    killpg=None,
    sleep=time.sleep,
    timeout_s: float = 15.0,
    commands=group_commands,
) -> bool:
    """begin_stop then finish_stop in one call. True when it signalled."""
    pgid = begin_stop(state_dir, echo, killpg=killpg, commands=commands)
    if pgid is None:
        return False
    finish_stop(
        state_dir, pgid, echo, killpg=killpg, sleep=sleep, timeout_s=timeout_s, commands=commands
    )
    return True


def _clear_pid_file(state_dir: Path, pid: int) -> None:
    """Remove the pid file only while it still names `pid` (a newer run may
    have rewritten it)."""
    pid_file = state_dir / PID_FILE
    try:
        recorded = pid_file.read_text().splitlines()[0].strip()
    except (OSError, IndexError):
        return
    if recorded == str(pid):
        pid_file.unlink(missing_ok=True)


def run_after_ready(
    *,
    client,
    pg: PgCoords,
    home: Path,
    env: dict,
    state_dir: Path,
    url: str,
    ready_timeout: float,
    echo: Callable[[str], None],
    wait=wait_ready,
    ensure_db=ensure_database,
    launch_fn=launch,
) -> None:
    """Thread body: the embedded Postgres only exists once the manager booted,
    so the database and Starflow wait for /ready. A failure is reported, never
    raised: the manager keeps serving without Starflow."""
    try:
        wait(client, timeout_s=ready_timeout)
        ensure_db(pg)
        proc = launch_fn(home, env, state_dir)
    except Exception as exc:
        echo(f"Starflow not started: {exc}")
        return
    echo(f"Starflow: {url}  (log: {log_path(state_dir)})")
    code = proc.wait()
    _clear_pid_file(state_dir, proc.pid)
    if code is not None and code < 0:
        # Killed by a signal: the teardown (`qod stop`, Ctrl-C) did it.
        echo("Starflow stopped.")
    else:
        echo(f"Starflow exited with code {code}; see {log_path(state_dir)}")
