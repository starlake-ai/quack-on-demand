"""qod stop: scripts/stop-jar.sh without the checkout.

Discovers the manager by its listening ports (it owns both the REST and the
FlightSQL edge port in one JVM) and the quack nodes by their spawn script,
SIGTERMs everything, waits up to FORCE_AFTER seconds, then escalates to
SIGKILL. Works regardless of how the manager was started (qod start, qod demo,
run-jar.sh).
"""

import os
import signal
import subprocess
import sys
import time

import typer


def _listening_pid(port: int) -> str | None:
    proc = subprocess.run(
        ["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
        capture_output=True,
        text=True,
    )
    return proc.stdout.split()[0] if proc.stdout.split() else None


def _pgrep(pattern: str) -> list[str]:
    proc = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True)
    return proc.stdout.split()


def _kill_all(pids: set[int], sig: signal.Signals) -> None:
    for pid in pids:
        try:
            os.kill(pid, sig)
        except (ProcessLookupError, PermissionError):
            pass


def stop():
    """Stop a running quack-on-demand manager and its quack nodes."""
    if sys.platform == "win32":
        typer.echo(
            "qod stop is not supported on Windows yet; use scripts/stop-jar.ps1 "
            "(or taskkill /T on the java process).",
            err=True,
        )
        raise typer.Exit(1)

    mgr_port = int(os.environ.get("QOD_ON_DEMAND_PORT", "20900"))
    edge_port = int(os.environ.get("PROXY_PORT", "31338"))
    force_after = int(os.environ.get("FORCE_AFTER", "10"))

    def discover() -> set[int]:
        pids = {_listening_pid(mgr_port), _listening_pid(edge_port), *_pgrep("spawn-quack-node")}
        return {int(p) for p in pids if p}

    pids = discover()
    if not pids:
        typer.echo(
            f"nothing running on ports {mgr_port} / {edge_port} "
            "and no spawn-quack-node processes."
        )
        return

    typer.echo(f"stopping pids: {' '.join(str(p) for p in sorted(pids))}")
    _kill_all(pids, signal.SIGTERM)

    for i in range(force_after):
        time.sleep(1)
        if not discover():
            typer.echo(f"stopped cleanly after {i + 1}s.")
            return

    typer.echo(f"still running after {force_after}s; sending SIGKILL.", err=True)
    # Re-discover: the spawn scripts trap TERM, but their duckdb children can
    # survive a killed wrapper; sweep both.
    leftovers = pids | discover() | {int(p) for p in _pgrep("^duckdb") if p}
    _kill_all(leftovers, signal.SIGKILL)
    time.sleep(2)
    if discover():
        typer.echo(
            f"WARN: a port is still listening. Investigate with: "
            f"lsof -nP -iTCP:{mgr_port},{edge_port} -sTCP:LISTEN",
            err=True,
        )
        raise typer.Exit(1)
    typer.echo("stopped (forced).")
