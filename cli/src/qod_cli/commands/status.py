"""`qod status`: one glance at what is (or is not) running.

Reports, in order of how much access it has, degrading gracefully at each
tier rather than failing:

  1. Local processes - which pids own the manager REST and FlightSQL ports
     (same lsof discovery `qod stop` uses; skipped on Windows).
  2. Manager REST - the unauthenticated `/health` (alive + pool/node counts)
     and `/ready` (503 until Postgres is reachable) probes the Helm chart
     uses, plus the public `/api/config/client` FlightSQL coordinates and a
     TCP probe of that edge port.
  3. Authenticated detail - per-pool healthy/total node rows from
     `/api/pool/list` when a token (a qod login session or a personal
     access token) is available; silently omitted otherwise.
  4. Stored `qod setup` config - how many vars, and where.
  5. Embedded control plane - filesystem ground truth from the pgdata
     directory `qod serve` provisions: presence, the port from its
     `postmaster.pid`, and a TCP liveness probe of that port.

Exit code: 0 when the manager REST answers `/health`, 1 when it does not,
so scripts can `qod status && ...`.
"""

from __future__ import annotations

import os
import socket
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

import httpx
import typer

from ..config import config_path, load_settings, load_start_env
from ..launcher import default_data_dir
from ..output import render


def _listening_pid(port: int) -> str | None:
    """Same discovery `qod stop` uses: the pid listening on a TCP port."""
    if sys.platform == "win32":
        return None
    proc = subprocess.run(
        ["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
        capture_output=True,
        text=True,
    )
    return proc.stdout.split()[0] if proc.stdout.split() else None


def _get_json(url: str) -> tuple[int, dict | None]:
    """GET returning (status_code, parsed json or None); (0, None) on
    connection failure. Never raises."""
    try:
        resp = httpx.get(url, timeout=5.0)
    except httpx.HTTPError:
        return 0, None
    try:
        return resp.status_code, resp.json() if resp.content else None
    except ValueError:
        return resp.status_code, None


def _tcp_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=2):
            return True
    except OSError:
        return False


def _embedded_postgres_port(pgdata: Path) -> int:
    """The port `postgres` is bound to, from `pgdata/postmaster.pid` line 4 (pid is
    line 1, port is line 4 - see the Postgres docs for the file's layout), falling
    back to QOD_PG_EMBEDDED_PORT / 25432 when the file is absent or unparseable
    (server not started yet, or mid-startup)."""
    try:
        return int(pgdata.joinpath("postmaster.pid").read_text().splitlines()[3])
    except (OSError, IndexError, ValueError):
        pass
    try:
        return int(os.environ.get("QOD_PG_EMBEDDED_PORT", "25432"))
    except ValueError:
        return 25432


def status(ctx: typer.Context):
    """Show whether a manager is running and what it is serving.

    Probes the active profile's manager URL (unauthenticated /health and
    /ready, the public FlightSQL coordinates, and a TCP check of the edge
    port), lists local manager pids on this machine, adds per-pool node
    health when logged in, reports the stored `qod setup` config, and
    probes the embedded control plane's pgdata directory for a live port.
    Exits 1 when the manager is unreachable."""
    settings = load_settings()
    base = settings.manager_url.rstrip("/")
    parsed = urlparse(base)
    manager_host = parsed.hostname or "localhost"

    out: dict = {"managerUrl": base}

    rest_pid = _listening_pid(parsed.port or 80)
    out["localManagerPid"] = rest_pid

    health_code, health = _get_json(f"{base}/health")
    if health_code == 200 and isinstance(health, dict):
        out["manager"] = "up"
        out["pools"] = health.get("poolsCount")
        out["nodes"] = health.get("nodesCount")
    else:
        out["manager"] = "unreachable" if health_code == 0 else f"http {health_code}"

    ready_code, _ = _get_json(f"{base}/ready")
    out["ready"] = (
        True if ready_code == 200 else False if ready_code == 503 else None
    )

    cfg_code, cfg = _get_json(f"{base}/api/config/client")
    if cfg_code == 200 and isinstance(cfg, dict):
        edge_host = cfg.get("flightSqlHost") or manager_host
        if edge_host == "0.0.0.0":
            edge_host = manager_host
        edge_port = int(cfg.get("flightSqlPort") or 31338)
        out["flightsql"] = f"{edge_host}:{edge_port}"
        out["flightsqlTls"] = cfg.get("flightSqlTls")
        out["flightsqlListening"] = _tcp_open(edge_host, edge_port)
        edge_pid = _listening_pid(edge_port) if edge_host in ("localhost", "127.0.0.1") else None
        if edge_pid:
            out["localEdgePid"] = edge_pid

    pool_rows: list[dict] = []
    if out["manager"] == "up" and settings.token:
        try:
            from ..rest import RestClient

            pools = RestClient(settings).request("GET", "/api/pool/list")
            for pool in (pools or {}).get("pools", []):
                nodes = pool.get("nodes", [])
                pool_rows.append(
                    {
                        "tenant": pool.get("tenant"),
                        "pool": pool.get("pool"),
                        "nodesHealthy": sum(1 for n in nodes if n.get("healthy")),
                        "nodesTotal": len(nodes),
                    }
                )
        except Exception:
            # Auth expired, insufficient role, older manager: the
            # unauthenticated summary above still stands.
            pool_rows = []
    if pool_rows:
        out["poolDetail"] = pool_rows

    start_env = load_start_env()

    # `qod serve` persists nothing to the [start] table, so there is
    # no stored QOD_PG_EMBEDDED* state to read here - the filesystem is the only
    # ground truth, and it is also the one source that still answers when the
    # manager (and so the process that would have reported its own coordinates) is
    # NOT running, which is the moment this line matters most. The dir itself is
    # still resolved with the same precedence `qod serve` uses - a real env var over
    # a value `qod setup` persisted - so a `qod setup --set QOD_PG_EMBEDDED_DATA_DIR`
    # followed by `qod serve` in a fresh shell is still found. Liveness is a TCP
    # probe, not os.kill(pid, 0): on Windows any non-CTRL signal value TERMINATES
    # the target process, so a "liveness check" there would kill the server. A
    # stale postmaster.pid surviving an unclean shutdown, with some unrelated
    # process now listening on that port, reads as "running" - an accepted false
    # positive, not worth a second liveness signal to close.
    merged = {**start_env, **os.environ}
    raw_embedded_dir = (merged.get("QOD_PG_EMBEDDED_DATA_DIR") or "").strip()
    embedded_dir = raw_embedded_dir or str(default_data_dir() / "pg")
    pgdata = Path(embedded_dir) / "pgdata"
    if pgdata.is_dir():
        port = _embedded_postgres_port(pgdata)
        out["embeddedPostgres"] = (
            f"running (localhost:{port})"
            if _tcp_open("localhost", port)
            else "stopped (data preserved)"
        )
        out["embeddedPostgresDir"] = embedded_dir

    out["setupVars"] = len(start_env)
    out["configFile"] = str(config_path())

    render(out, ctx.obj.json_output)
    if out["manager"] != "up":
        raise typer.Exit(1)
