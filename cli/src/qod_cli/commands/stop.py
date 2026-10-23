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
stop above usually reaps it as part of the JVM's own shutdown, but not always:
`_stop_manager_and_nodes` returns as soon as the ports are free, which can
precede the embedded postmaster's own exit, so this sweep can also fire a
SIGTERM (and, after 5s, SIGKILL) on a normal stop against a postmaster that is
already shutting down on its own. That is recoverable (crash-safe WAL replay
on the next start) and mostly a Windows concern in practice, but it is not
limited to the orphan case.
"""

import csv
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
    """pid from line 1 of postmaster.pid; None on a missing or garbled file, or
    on a pid <= 0. 0 and negative pids are not garbage to guard against out of
    caution - `os.kill` gives them special, dangerous meanings: 0 signals every
    process in the caller's own process group (the shell job running this CLI),
    and a negative pid signals every process in THAT process group ("all
    processes the caller may signal" for -1). Neither is ever a real, single
    postmaster pid."""
    try:
        pid = int(pgdata.joinpath("postmaster.pid").read_text().splitlines()[0])
    except (OSError, IndexError, ValueError):
        return None
    return pid if pid > 0 else None


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


def _process_name(pid: int) -> str | None:
    """The command/image name currently owning `pid`, or None when the lookup
    itself fails for any reason (no such pid, permission denied, the probe tool
    missing) - fail-safe: an unknown name must never be treated as a match, so
    a failed lookup means "not a postmaster", not "assume it is"."""
    try:
        if sys.platform == "win32":
            proc = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV"],
                capture_output=True, text=True,
            )
            rows = list(csv.reader(proc.stdout.splitlines()))
            for row in rows[1:]:  # row[0] is the header line ("Image Name", "PID", ...)
                if len(row) >= 2 and row[1] == str(pid):
                    return row[0]
            return None
        proc = subprocess.run(
            ["ps", "-p", str(pid), "-o", "comm="], capture_output=True, text=True
        )
        if proc.returncode != 0:
            return None
        name = proc.stdout.strip()
        return name or None
    except Exception:
        return None


def _is_postmaster_name(name: str | None) -> bool:
    """True when `name` (from `_process_name`) is a postgres server binary.
    Accepts an exact match or a basename match (some `ps` builds report the
    full path)."""
    if name is None:
        return False
    if sys.platform == "win32":
        return name.strip().strip('"').lower() == "postgres.exe"
    return name.strip().rsplit("/", 1)[-1] == "postgres"


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


def sweep_orphaned_embedded_postgres(
    is_alive=None, process_name=None, terminate=None, echo=None
) -> None:
    """Kill an orphaned embedded-control-plane postmaster left behind by an
    abrupt JVM death. `is_alive`, `process_name`, and `terminate` are
    injectable so tests never touch a real process; None (the default used by
    `perform_stop`) resolves the current module-level implementation at call
    time, so monkeypatching `_pid_alive` / `_process_name` / `_terminate_pid`
    on the module still takes effect.

    The pid in postmaster.pid can be recycled - that is the feature's own
    premise, since an abrupt JVM death is what leaves the file behind - so
    "some process has this pid" (`is_alive`) is not enough; this also checks
    that the process is still actually a postgres server before ever signaling
    it. A failed or exception-raising identity lookup is treated the same as a
    mismatch: never kill a pid whose identity could not be verified.
    """
    is_alive = is_alive if is_alive is not None else _pid_alive
    process_name = process_name if process_name is not None else _process_name
    terminate = terminate if terminate is not None else _terminate_pid
    echo = echo if echo is not None else typer.echo
    pid = _read_postmaster_pid(_embedded_pgdata_dir())
    if pid is None or not is_alive(pid):
        return
    try:
        name = process_name(pid)
    except Exception:
        name = None
    if not _is_postmaster_name(name):
        echo(f"pid {pid} from postmaster.pid is now {name or 'unknown'}; not touching it")
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
    stopping a detached one behave identically. Starflow, a client of the
    manager, is sent SIGTERM first, but its bounded wait comes last: the
    manager and node stop must neither be delayed by it nor skipped when a
    second Ctrl-C interrupts it (that would orphan duckdb nodes on 21900+).
    The same holds for a Ctrl-C landing while the SIGTERM step runs its `ps`."""
    pgid = None
    try:
        pgid = _begin_starflow_stop()
    finally:
        try:
            try:
                _stop_manager_and_nodes()
            finally:
                sweep_orphaned_embedded_postgres()
        finally:
            if pgid is not None:
                _finish_starflow_stop(pgid)


def _starflow_state_dir() -> Path:
    """Seam: tests point this away from the real qod data dir."""
    from .. import launcher

    return launcher.default_data_dir()


def _begin_starflow_stop() -> int | None:
    """SIGTERM a Starflow started by --with-starflow, if any. A failure here must
    never keep the manager running."""
    from .. import starflow

    try:
        return starflow.begin_stop(_starflow_state_dir(), echo=typer.echo)
    except Exception as exc:
        typer.echo(f"WARN: could not stop Starflow: {exc}", err=True)
        return None


def _finish_starflow_stop(pgid: int) -> None:
    from .. import starflow

    try:
        starflow.finish_stop(_starflow_state_dir(), pgid, echo=typer.echo)
    except Exception as exc:
        typer.echo(f"WARN: could not stop Starflow: {exc}", err=True)


def _stop_manager_and_nodes():
    # Same resolution as `qod start` and `qod status`: a real env var over a value `qod setup`
    # persisted, over the default. Reading the environment alone stopped (and, for nodes, scoped
    # to) port 20900 when the manager had been set up on another port.
    merged = {**load_start_env(), **os.environ}
    mgr_port = int(merged.get("QOD_ON_DEMAND_PORT") or "20900")
    edge_port = int(merged.get("PROXY_PORT") or "31338")
    force_after = int(merged.get("FORCE_AFTER") or "10")

    # Only this manager's nodes: each wrapper's argv and node directory carry the manager's marker
    # `<rest-port>-<boot id>` (LocalQuackBackend.managerMarker), so another manager on this host
    # (an isolated test stack) keeps its nodes.
    spawn_re = rf"spawn-quack-node\.sh [0-9]+ {mgr_port}-[0-9a-f]+"
    node_re = rf"(qod-node .*--secret-directory|duckdb .*-init) .*quack-node\.{mgr_port}-[0-9a-f]+\."

    def discover() -> set[int]:
        pids = {_listening_pid(mgr_port), _listening_pid(edge_port), *_pgrep(spawn_re)}
        return {int(p) for p in pids if p}

    # Unmarked nodes (a manager older than the marker) are left alone, as stop-jar.sh does; say so.
    unmarked = [p for p in _pgrep(r"spawn-quack-node\.sh [0-9]+$") if p]
    if unmarked:
        typer.echo(
            f"note: leaving {len(unmarked)} unmarked spawn-quack-node.sh process(es) alone "
            "(an older manager's; scripts/kill-quack-nodes.sh --unmarked stops them)."
        )

    pids = discover()
    if not pids:
        typer.echo(
            f"nothing running on ports {mgr_port} / {edge_port} "
            "and no spawn-quack-node processes of that manager."
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
    # Re-discover: the spawn scripts trap TERM, but a node process could outlive a killed wrapper for
    # a moment; sweep both. A node runs as `<abs path>/qod-node ... --secret-directory
    # <tmp>/quack-node.<marker>.XXX/secrets/...` (an earlier release's as `<abs path>/duckdb -init
    # <tmp>/quack-node.<marker>.XXX/init`), so match that argv with this manager's marker (a bare
    # `^qod-node` never matches the absolute path the spawn script uses).
    leftovers = pids | discover() | {int(p) for p in _pgrep(node_re) if p}
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
