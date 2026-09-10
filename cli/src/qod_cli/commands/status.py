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
     `/api/pool/list` when an API key or session token is available;
     silently omitted otherwise.
  4. Stored `qod setup` config - how many vars, and where.

Exit code: 0 when the manager REST answers `/health`, 1 when it does not,
so scripts can `qod status && ...`.
"""

from __future__ import annotations

import socket
import subprocess
import sys
from urllib.parse import urlparse

import httpx
import typer

from ..config import config_path, load_settings, load_start_env
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


def status(ctx: typer.Context):
    """Show whether a manager is running and what it is serving.

    Probes the active profile's manager URL (unauthenticated /health and
    /ready, the public FlightSQL coordinates, and a TCP check of the edge
    port), lists local manager pids on this machine, adds per-pool node
    health when logged in, and reports the stored `qod setup` config.
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
    if out["manager"] == "up" and (settings.api_key or settings.token):
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
    out["setupVars"] = len(start_env)
    out["configFile"] = str(config_path())

    render(out, ctx.obj.json_output)
    if out["manager"] != "up":
        raise typer.Exit(1)
