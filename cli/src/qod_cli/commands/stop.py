"""qod stop: scripts/stop-jar.sh without the checkout.

Discovers the manager by its listening ports (it owns both the REST and the
FlightSQL edge port in one JVM) and the quack nodes by their spawn script,
SIGTERMs everything, waits up to FORCE_AFTER seconds, then escalates to
SIGKILL. Works regardless of how the manager was started (qod start, qod start --demo,
run-jar.sh).

Finally sweeps an orphaned embedded control-plane postmaster (`qod serve` /
QOD_PG_EMBEDDED): a JVM that dies abruptly (the pre-0.9.2 native-load crash is
the field case that surfaced this) leaves that process running with no port of
its own the discovery above would ever find, and the next `qod serve` then
refuses with the live-pid guard until a manual kill. The POSIX manager/node
stop above normally reaps it as part of the JVM's own shutdown; this sweep
only fires in the orphan case, which is primarily a Windows scenario.
"""

import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import typer

from ..config import load_start_env
from ..launcher import default_data_dir


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


def _embedded_pgdata_dir() -> Path:
    """Same resolution `qod status` uses: a real env var over a value `qod setup`
    persisted, over the built-in default under the data dir."""
    merged = {**load_start_env(), **os.environ}
    raw = (merged.get("QOD_PG_EMBEDDED_DATA_DIR") or "").strip()
    embedded_dir = raw or str(default_data_dir() / "pg")
    return Path(embedded_dir) / "pgdata"


def _read_postmaster_pid(pgdata: Path) -> int | None:
    """pid from line 1 of postmaster.pid; None on a missing or garbled file."""
    try:
        return int(pgdata.joinpath("postmaster.pid").read_text().splitlines()[0])
    except (OSError, IndexError, ValueError):
        return None


def _pid_alive(pid: int) -> bool:
    if sys.platform == "win32":
        # No signal-0 probe on win32; ask the OS via tasklist instead of guessing.
        proc = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True
        )
        return str(pid) in proc.stdout
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True  # exists, just not ours to signal


def _terminate_pid(pid: int, is_alive=_pid_alive, sleep=time.sleep) -> None:
    """SIGTERM -> wait up to 5s -> SIGKILL on POSIX. `taskkill /F` on win32: never
    os.kill there, since any non-CTRL signal value terminates the target process
    unconditionally anyway, and taskkill is this repo's established convention."""
    if sys.platform == "win32":
        subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        return
    for _ in range(5):
        if not is_alive(pid):
            return
        sleep(1)
    try:
        os.kill(pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def sweep_orphaned_embedded_postgres(is_alive=None, terminate=None, echo=None) -> None:
    """Kill an orphaned embedded-control-plane postmaster left behind by an
    abrupt JVM death. `is_alive` and `terminate` are injectable so tests never
    touch a real process; None (the default used by `perform_stop`) resolves the
    current module-level implementation at call time, so monkeypatching
    `_pid_alive` / `_terminate_pid` on the module still takes effect."""
    is_alive = is_alive if is_alive is not None else _pid_alive
    terminate = terminate if terminate is not None else _terminate_pid
    echo = echo if echo is not None else typer.echo
    pid = _read_postmaster_pid(_embedded_pgdata_dir())
    if pid is None or not is_alive(pid):
        return
    echo(f"stopping orphaned embedded postgres (pid {pid})")
    terminate(pid)


def stop():
    """Stop a running quack-on-demand manager and its quack nodes."""
    if sys.platform == "win32":
        typer.echo(
            "qod stop is not supported on Windows yet; use scripts/stop-jar.ps1 "
            "(or taskkill /T on the java process).",
            err=True,
        )
        raise typer.Exit(1)
    perform_stop()


def perform_stop():
    """The teardown itself, POSIX only. Shared between the `qod stop` command
    and the Ctrl-C path of `qod start` / `qod start --demo` (see
    `_launch._run_supervised`), so interrupting a foreground manager and
    stopping a detached one behave identically."""
    try:
        _stop_manager_and_nodes()
    finally:
        sweep_orphaned_embedded_postgres()


def _stop_manager_and_nodes():
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
