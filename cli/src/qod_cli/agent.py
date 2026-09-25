"""qod agent: one quack node per server, assigned by the manager.

Loop: heartbeat, diff the reply's assignment with the running node, act, sleep.
Restarting a crashed node is this process's job; the manager only ever sees
state reports. POSIX only (Linux, macOS). Protocol:
docs/superpowers/specs/2026-09-25-fleet-backend-design.md.
"""

from __future__ import annotations

import collections
import os
import platform
import signal
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Callable

import httpx

from . import __version__

STOP_GRACE_S = 60
START_GRACE_S = 60
BACKOFF_MIN_S = 5
BACKOFF_MAX_S = 300
STDERR_TAIL = 20
PIDFILE = "node.pid"


def default_advertise_host() -> str:
    """First non-loopback IPv4 of this host, via a connectionless UDP socket."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())
    finally:
        s.close()


def host_capacity() -> tuple[int | None, int | None]:
    """(logical cores, physical RAM bytes); either None when the platform will not say."""
    cpus = os.cpu_count()
    try:
        mem = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (ValueError, OSError, AttributeError):
        mem = None
    return cpus, mem


def _port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.25):
            return True
    except OSError:
        return False


def _cmdline(pid: int) -> str:
    """Command line of `pid`, empty when it is gone. /proc on Linux, ps on macOS."""
    proc = Path(f"/proc/{pid}/cmdline")
    if proc.exists():
        try:
            return proc.read_bytes().replace(b"\0", b" ").decode(errors="replace")
        except OSError:
            return ""
    try:
        out = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True, timeout=5)
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


class _Node:
    def __init__(self, assignment: dict, proc, started_at: float):
        self.assignment, self.proc, self.started_at = assignment, proc, started_at
        self.wall_started_at = time.time()
        self.state = "starting"
        self.error: str | None = None
        self.stderr = collections.deque(maxlen=STDERR_TAIL)
        if getattr(proc, "stderr", None) is not None and hasattr(proc.stderr, "readline"):
            threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self):
        for line in iter(self.proc.stderr.readline, b""):
            text = line.decode(errors="replace")
            self.stderr.append(text.rstrip())
            sys.stderr.write(text)

    def tail(self) -> str:
        lines = list(self.stderr) or [l.decode(errors="replace").rstrip() for l in getattr(self.proc, "stderr_lines", [])]
        return "\n".join(lines)


class Agent:
    def __init__(self, manager_url: str, join_token: str, *, name: str, advertise_host: str, bind_host: str,
                 node_port: int, spawn_script: Path, duckdb_bin: Path | None, state_dir: Path, insecure: bool,
                 http=httpx, popen=subprocess.Popen, sleep: Callable[[float], None] = time.sleep,
                 port_open: Callable[[int], bool] | None = None, clock: Callable[[], float] = time.monotonic,
                 capacity: Callable[[], tuple[int | None, int | None]] = host_capacity,
                 duckdb_version: str | None = None):
        if manager_url.startswith("http://") and not insecure:
            sys.stderr.write("qod agent: refusing a plain http:// manager URL (the assignment carries credentials); pass --insecure to override\n")
            raise SystemExit(2)
        self.manager_url, self.join_token = manager_url.rstrip("/"), join_token
        self.name, self.advertise_host, self.bind_host, self.node_port = name, advertise_host, bind_host, node_port
        self.spawn_script, self.duckdb_bin, self.state_dir = spawn_script, duckdb_bin, Path(state_dir)
        self.http, self.popen, self.sleep, self.clock, self.capacity = http, popen, sleep, clock, capacity
        probe_host = "127.0.0.1" if bind_host == "0.0.0.0" else bind_host
        self.port_open = port_open or (lambda p: _port_open(probe_host, p))
        self.node: _Node | None = None
        self.last_report: dict = {"assignmentEpoch": 0, "nodeId": None, "state": "none", "pid": None, "error": None, "startedAt": None}
        self.duckdb_version = duckdb_version
        self.failures = 0
        # Monotonic deadline of the scheduled restart of a failed node; None while none is scheduled.
        self.next_restart_at: float | None = None
        self.state_dir.mkdir(parents=True, exist_ok=True)

    # ---- pidfile ----
    @property
    def pidfile(self) -> Path:
        return self.state_dir / PIDFILE

    def reap_orphan(self) -> int | None:
        """Kill a node left behind by a previous agent, identified by the pidfile and a command
        line that still names the spawn script (a recycled pid is left alone). Always removes
        the file. Returns the pid it killed."""
        if not self.pidfile.exists():
            return None
        try:
            pid = int(self.pidfile.read_text().strip())
        except ValueError:
            pid = None
        self.pidfile.unlink(missing_ok=True)
        if pid is None or "spawn-quack-node" not in _cmdline(pid):
            return None
        sys.stderr.write(f"qod agent: reaping orphan node pid {pid} from a previous agent\n")
        # TERM the script so its trap stops duckdb and removes the FIFO; if it lingers, KILL its
        # whole process group (it leads its own session), which takes the duckdb grandchild too.
        for target, sig in ((pid, signal.SIGTERM), (-pid, signal.SIGKILL)):
            try:
                os.kill(target, sig)
            except ProcessLookupError:
                break
            for _ in range(50):
                if not _cmdline(pid):
                    return pid
                self.sleep(0.2)
        return pid

    # ---- reporting ----
    def _report(self) -> dict:
        n = self.node
        if n is None:
            return self.last_report
        alive = n.proc.poll() is None
        if n.state == "starting":
            if not alive:
                n.state, n.error = "failed", f"exited with {n.proc.poll()}: {n.tail()}"
            elif self.port_open(n.assignment["port"]):
                n.state = "running"
            elif self.clock() - n.started_at > START_GRACE_S:
                n.state, n.error = "failed", f"port {n.assignment['port']} not open after {START_GRACE_S}s: {n.tail()}"
        elif n.state == "running" and not alive:
            n.state, n.error = "failed", f"exited with {n.proc.poll()}: {n.tail()}"
        return {"assignmentEpoch": n.assignment["epoch"], "nodeId": n.assignment["nodeId"], "state": n.state,
                "pid": n.proc.pid if alive else None, "error": n.error,
                "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(n.wall_started_at))}

    # ---- process control ----
    def _start(self, assignment: dict) -> None:
        env = dict(os.environ)
        env.update(assignment["env"])
        env["kind"] = assignment["kind"]
        env["QOD_NODE_BIND"] = self.bind_host
        for key in ("dbInitSql", "objectStoreSql", "extraSetupSql", "lockdownSql"):
            if assignment.get(key):
                env[key] = assignment[key]
        if self.duckdb_bin is not None:
            env["DUCKDB_BIN"] = str(self.duckdb_bin)
            env["PATH"] = str(Path(self.duckdb_bin).parent) + os.pathsep + env.get("PATH", "")
        cmd = ["bash", str(self.spawn_script), str(assignment["port"]), assignment["token"]]
        # Own session: a manager-side stop reaches the node through this agent, never through a
        # terminal signal. The pidfile is what lets the NEXT agent find it if this one dies.
        proc = self.popen(cmd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                          start_new_session=True)
        self.pidfile.write_text(f"{proc.pid}\n")
        self.node = _Node(assignment, proc, self.clock())

    def _stop(self) -> None:
        n = self.node
        if n is None:
            return
        if n.proc.poll() is None:
            n.proc.terminate()
            try:
                n.proc.wait(timeout=STOP_GRACE_S)
            except subprocess.TimeoutExpired:
                # The script leads its own session: KILL the group so duckdb dies with it.
                try:
                    os.killpg(n.proc.pid, signal.SIGKILL)
                except OSError:
                    n.proc.kill()
                n.proc.wait()
        self.pidfile.unlink(missing_ok=True)
        self.last_report = {"assignmentEpoch": n.assignment["epoch"], "nodeId": n.assignment["nodeId"],
                            "state": "stopped", "pid": None, "error": None, "startedAt": None}
        self.node = None

    # ---- reconciliation ----
    def _reconcile(self, assignment: dict | None) -> None:
        n = self.node
        if assignment is None:
            if n is not None:
                self._stop()
            self.failures, self.next_restart_at = 0, None
        elif n is None or n.assignment["epoch"] != assignment["epoch"]:
            if n is not None:
                self._stop()
            self.failures, self.next_restart_at = 0, None
            self._start(assignment)
        elif n.state == "failed":
            # First sight of a failure schedules the restart (5 s, doubling to 5 min); a later
            # heartbeat past the deadline performs it. The failed state is reported meanwhile.
            if self.next_restart_at is None:
                self.failures += 1
                delay = min(BACKOFF_MIN_S * (2 ** (self.failures - 1)), BACKOFF_MAX_S)
                self.next_restart_at = self.clock() + delay
                sys.stderr.write(f"qod agent: node failed ({n.error}); restarting in {delay}s\n")
            elif self.clock() >= self.next_restart_at:
                self._stop()
                self._start(assignment)
                self.next_restart_at = None
        elif n.state == "running":
            self.failures = 0

    def run_once(self) -> float:
        cpus, mem = self.capacity()
        body = {"name": self.name, "advertiseHost": self.advertise_host, "nodePort": self.node_port,
                "agentVersion": __version__, "os": f"{sys.platform}-{platform.machine()}",
                "duckdbVersion": self.duckdb_version, "cpus": cpus, "memoryBytes": mem, "node": self._report()}
        try:
            r = self.http.post(f"{self.manager_url}/api/fleet/heartbeat", json=body,
                               headers={"X-Fleet-Token": self.join_token}, timeout=10.0)
        except Exception as exc:  # network: keep the node running, retry later
            sys.stderr.write(f"qod agent: heartbeat failed: {exc}\n")
            return BACKOFF_MIN_S
        if not r.is_success:
            sys.stderr.write(f"qod agent: manager answered {r.status_code}: {getattr(r, 'text', '')}\n")
            return BACKOFF_MIN_S
        reply = r.json()
        self._reconcile(reply.get("assignment"))
        return float(reply.get("heartbeatSec", 5))

    def run_forever(self) -> None:
        self.reap_orphan()
        sys.stderr.write(f"qod agent: server '{self.name}', advertise host {self.advertise_host}, node binds "
                         f"{self.bind_host}:{self.node_port} (override with --advertise-host / --bind-host if "
                         f"this is not the data interface)\n")
        try:
            while True:
                self.sleep(self.run_once())
        finally:
            self._stop()
