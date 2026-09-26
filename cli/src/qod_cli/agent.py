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
DRAIN_JOIN_S = 0.5

# The node's environment is built from this allowlist, never from the agent's whole environment:
# the agent holds QOD_FLEET_JOIN_TOKEN (the fleet-wide credential), and tenant SQL on a node
# without lockdown can read /proc/self/environ.
_ENV_PASSTHROUGH = (
    "PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ", "USER", "LOGNAME", "SHELL",
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
    "DUCKDB_BIN", "QOD_APP_HOME",
    # The spawn script's CREATE DATABASE admin db, and libpq / OpenSSL settings for a metastore
    # reached over TLS: without them every node of the fleet fails, silently.
    "PG_ADMIN_DB", "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT", "PGSSLKEY", "PGCONNECT_TIMEOUT",
    "SSL_CERT_FILE",
)
# Object-storage settings the spawn script reads.
_ENV_PASSTHROUGH_PREFIXES = ("QOD_S3_", "QOD_AZURE_")
_SQL_KEYS = ("dbInitSql", "objectStoreSql", "extraSetupSql", "lockdownSql")


def _no_advertise_host(reason: str) -> SystemExit:
    sys.stderr.write(f"qod agent: cannot pick an advertise host ({reason}); pass --advertise-host "
                     f"with the address the manager should dial\n")
    return SystemExit(2)


def default_advertise_host() -> str:
    """First non-loopback IPv4 of this host, via a connectionless UDP socket. Exits 2 with a
    message naming --advertise-host when only a loopback address (or none) can be found."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        host = s.getsockname()[0]
    except OSError:
        try:
            host = socket.gethostbyname(socket.gethostname())
        except OSError as exc:  # socket.gaierror is an OSError
            raise _no_advertise_host(f"hostname does not resolve: {exc}") from None
    finally:
        s.close()
    if host.startswith("127.") or host == "0.0.0.0":
        raise _no_advertise_host(f"only found {host}")
    return host


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


def _identity(assignment: dict) -> tuple:
    """What makes an assignment a different node to run. The epoch alone is not enough: a server
    removed while unreachable rejoins with its epoch reset, so its next claim can carry the same
    epoch (and even the same node id) with a new token the old process does not accept."""
    return assignment["epoch"], assignment["nodeId"], assignment["token"]


def _error_of(r) -> str:
    """The manager's JSON error code and message (`address_change_refused: ...`), so a
    mis-addressed or mis-tokened agent is diagnosable from its log; the raw body otherwise."""
    try:
        body = r.json()
    except (ValueError, TypeError, AttributeError):
        body = None
    if isinstance(body, dict) and body.get("error"):
        return f"{body['error']}: {body.get('message', '')}"
    return getattr(r, "text", "") or ""


class _Node:
    def __init__(self, assignment: dict, proc, started_at: float):
        self.assignment, self.proc, self.started_at = assignment, proc, started_at
        self.wall_started_at = time.time()
        self.state = "starting"
        self.error: str | None = None
        self.stderr = collections.deque(maxlen=STDERR_TAIL)
        self.drainer: threading.Thread | None = None
        if getattr(proc, "stderr", None) is not None:
            self.drainer = threading.Thread(target=self._drain, daemon=True)
            self.drainer.start()

    def _drain(self):
        for line in iter(self.proc.stderr.readline, b""):
            text = line.decode(errors="replace")
            self.stderr.append(text.rstrip())
            sys.stderr.write(text)

    def tail(self) -> str:
        return "\n".join(self.stderr)

    def exit_error(self) -> str:
        # The process is gone: give the drain thread a moment to read the last lines it wrote.
        if self.drainer is not None:
            self.drainer.join(DRAIN_JOIN_S)
        return f"exited with {self.proc.poll()}: {self.tail()}"


class Agent:
    def __init__(self, manager_url: str, join_token: str, *, name: str, advertise_host: str, bind_host: str,
                 node_port: int, spawn_script: Path, duckdb_bin: Path | None, state_dir: Path, insecure: bool,
                 http=httpx, popen=subprocess.Popen, sleep: Callable[[float], None] = time.sleep,
                 port_open: Callable[[int], bool] | None = None, clock: Callable[[], float] = time.monotonic,
                 capacity: Callable[[], tuple[int | None, int | None]] = host_capacity,
                 duckdb_version: str | None = None):
        if manager_url.lower().startswith("http://") and not insecure:
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
        # None until the first successful heartbeat, then False while heartbeats fail: drives the
        # one-line "connected" / "reconnected" status, never repeated on every heartbeat.
        self.connected: bool | None = None
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
        # pid > 1 guards the -pid group kill below: -1 would signal every process we may signal.
        if pid is None or pid <= 1 or "spawn-quack-node" not in _cmdline(pid):
            return None
        sys.stderr.write(f"qod agent: reaping orphan node pid {pid} from a previous agent\n")
        # TERM the script so its trap stops duckdb and removes the FIFO; if it lingers, KILL its
        # whole process group (it leads its own session), which takes the duckdb grandchild too.
        for target, sig in ((pid, signal.SIGTERM), (-pid, signal.SIGKILL)):
            try:
                os.kill(target, sig)
            except ProcessLookupError:
                break
            for _ in range(int(STOP_GRACE_S / 0.2)):
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
                n.state, n.error = "failed", n.exit_error()
            elif self.port_open(n.assignment["port"]):
                n.state = "running"
            elif self.clock() - n.started_at > START_GRACE_S:
                n.state, n.error = "failed", f"port {n.assignment['port']} not open after {START_GRACE_S}s: {n.tail()}"
        elif n.state == "running" and not alive:
            n.state, n.error = "failed", n.exit_error()
        return {"assignmentEpoch": n.assignment["epoch"], "nodeId": n.assignment["nodeId"], "state": n.state,
                "pid": n.proc.pid if alive else None, "error": n.error,
                "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(n.wall_started_at))}

    # ---- process control ----
    def _launch_spec(self, assignment: dict) -> tuple[list[str], dict[str, str]]:
        """Command and environment for `assignment`. Raises KeyError/TypeError on a malformed
        assignment, before anything is stopped."""
        env = {k: v for k, v in os.environ.items()
               if k in _ENV_PASSTHROUGH or k.startswith(_ENV_PASSTHROUGH_PREFIXES)}
        env.update({str(k): str(v) for k, v in assignment["env"].items()})
        env["kind"] = str(assignment["kind"])
        env["QOD_NODE_BIND"] = self.bind_host
        # Always from the assignment, empty when absent: a stray value in the agent's environment
        # (a lockdownSql=... exported by hand) must never reach a node.
        for key in _SQL_KEYS:
            env[key] = str(assignment.get(key) or "")
        if self.duckdb_bin is not None:
            env["DUCKDB_BIN"] = str(self.duckdb_bin)
            env["PATH"] = str(Path(self.duckdb_bin).parent) + os.pathsep + env.get("PATH", "")
        cmd = ["bash", str(self.spawn_script), str(int(assignment["port"])), str(assignment["token"])]
        return cmd, env

    def _start(self, assignment: dict, spec: tuple[list[str], dict[str, str]] | None = None) -> None:
        cmd, env = spec or self._launch_spec(assignment)
        # Own session: a manager-side stop reaches the node through this agent, never through a
        # terminal signal. The pidfile is what lets the NEXT agent find it if this one dies.
        proc = self.popen(cmd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                          start_new_session=True)
        # Track the child before any fallible bookkeeping: an untracked child would be spawned
        # again on the next heartbeat. A missing pidfile only degrades orphan reaping after an
        # agent crash, so a write failure is a warning, not a failed start.
        self.node = _Node(assignment, proc, self.clock())
        try:
            self.pidfile.write_text(f"{proc.pid}\n")
        except OSError as exc:
            sys.stderr.write(f"qod agent: WARN could not write pidfile {self.pidfile}: {exc}; "
                             f"a crash of this agent would leave node pid {proc.pid} unreaped\n")

    def _stop(self) -> None:
        n = self.node
        if n is None:
            return
        if n.proc.poll() is None:
            n.proc.terminate()
            try:
                n.proc.wait(timeout=STOP_GRACE_S)
            except subprocess.TimeoutExpired:
                n.proc.kill()
                n.proc.wait()
        # The script leads its own session, so its pid is the group id. KILL the group even when
        # the script already exited: a surviving duckdb child would otherwise hold the port.
        # ESRCH (group already empty) is the normal case and harmless.
        if n.proc.pid > 1:
            try:
                os.killpg(n.proc.pid, signal.SIGKILL)
            except OSError:
                pass
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
        elif n is None or _identity(n.assignment) != _identity(assignment):
            spec = self._launch_spec(assignment)  # malformed: raise before stopping anything
            if n is not None:
                self._stop()
            self.failures, self.next_restart_at = 0, None
            self._start(assignment, spec)
        elif n.state == "failed":
            # First sight of a failure schedules the restart (5 s, doubling to 5 min); a later
            # heartbeat past the deadline performs it. The failed state is reported meanwhile.
            if self.next_restart_at is None:
                self.failures += 1
                delay = min(BACKOFF_MIN_S * (2 ** (self.failures - 1)), BACKOFF_MAX_S)
                self.next_restart_at = self.clock() + delay
                sys.stderr.write(f"qod agent: node failed ({n.error}); restarting in {delay}s\n")
            elif self.clock() >= self.next_restart_at:
                spec = self._launch_spec(assignment)
                self._stop()
                self._start(assignment, spec)
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
            self._mark_disconnected()
            return BACKOFF_MIN_S
        if not r.is_success:
            sys.stderr.write(f"qod agent: WARN manager answered {r.status_code}: {_error_of(r)}\n")
            self._mark_disconnected()
            return BACKOFF_MIN_S
        if not self.connected:
            verb = "connected" if self.connected is None else "reconnected"
            sys.stderr.write(f"qod agent: {verb} to manager {self.manager_url} as server '{self.name}'\n")
            self.connected = True
        # One bad reply (not JSON, a malformed assignment) or a failed spawn/pidfile write must not
        # kill the agent: log it, keep whatever node runs, and heartbeat again after the backoff.
        try:
            reply = r.json()
            self._reconcile(reply.get("assignment"))
            return float(reply.get("heartbeatSec", 5))
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as exc:
            sys.stderr.write(f"qod agent: could not act on the manager's reply: {exc!r}\n")
            return BACKOFF_MIN_S

    def _mark_disconnected(self) -> None:
        if self.connected:
            self.connected = False

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
