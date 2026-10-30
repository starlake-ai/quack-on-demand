"""qod fleet join: one quack node per server, assigned by the manager.

Loop: heartbeat, diff the reply's assignment with the running node, act, sleep.
Restarting a crashed node is this process's job; the manager only ever sees
state reports. POSIX only (Linux, macOS). Protocol:
docs/superpowers/specs/2026-09-25-fleet-backend-design.md.
"""

from __future__ import annotations

import collections
import math
import os
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from secrets import token_hex as _token_hex
from typing import Callable

import httpx

from . import __version__
from .fix_actions import action_lines

STOP_GRACE_S = 60
START_GRACE_S = 60
BACKOFF_MIN_S = 5
BACKOFF_MAX_S = 300
STDERR_TAIL = 20
PIDFILE = "node.pid"

# A failed node's error (exit status and stderr tail) -> the fix to suggest; first match wins.
_NODE_FAILURES: tuple[tuple[re.Pattern, str], ...] = (
    (re.compile(r"qod-node not found"),
     "qod-node is missing on this server: re-run qod fleet join without --qod-node-bin to use the "
     "one qod bundles, or pass --qod-node-bin <path-to-qod-node>"),
    (re.compile(r"Library not loaded|cannot open shared object file|loaded .* install the matching libduckdb"),
     "qod-node cannot load its libduckdb: re-run qod fleet join without --qod-node-bin to let qod "
     "provision both, or put the matching libduckdb in the lib/ directory beside its bin/"),
    (re.compile(r"address already in use", re.I),
     "another process holds port {port} on this server: stop it, or re-run qod fleet join with "
     "--node-port <free-port>"),
    (re.compile(r"failed to (download|install) extension|extensions\.duckdb\.org", re.I),
     "this server cannot download DuckDB extensions: allow outbound HTTPS to extensions.duckdb.org"),
    (re.compile(r"unable to connect to postgres|could not connect to server|connection to server at", re.I),
     "this server cannot reach the catalog Postgres: open the route from this server, or set "
     "QOD_FLEET_NODE_PG_HOST / QOD_FLEET_NODE_PG_PORT on the manager"),
)


def node_failure_action(error: str | None, port: int) -> str | None:
    """The suggested fix for a failed node, from its error text; None when the failure is not one
    of the known classes (the error's stderr tail is then all the operator gets, as before)."""
    if not error:
        return None
    for pattern, action in _NODE_FAILURES:
        if pattern.search(error):
            return action.format(port=port)
    return None
DRAIN_JOIN_S = 0.5

# The node's environment is built from this allowlist, never from this process's whole environment:
# the join process holds QOD_FLEET_JOIN_TOKEN (the fleet-wide credential), which no node's process
# environment may hold.
_ENV_PASSTHROUGH = (
    "PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ", "USER", "LOGNAME", "SHELL",
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
    # The spawn script's CREATE DATABASE admin db, and libpq / OpenSSL settings for a metastore
    # reached over TLS: without them every node of the fleet fails, silently.
    "PG_ADMIN_DB", "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT", "PGSSLKEY", "PGCONNECT_TIMEOUT",
    "SSL_CERT_FILE",
)
# Object-storage settings the spawn script reads.
_ENV_PASSTHROUGH_PREFIXES = ("QOD_S3_", "QOD_AZURE_")
_SQL_KEYS = ("resourceSql",)
# The tenant-db's and the pool's initSql: assignment key, its trust key, and the secret file it goes
# to by trust. The file's name is the trust: the spawn script runs a ...Trusted file as written (a
# superuser's or the manager's text) and any other under qod-node's tenant initSql allowlist. Trusted
# only for a JSON true.
_TENANT_INIT_KEYS = (("tenantDbInitSql", "tenantDbInitTrusted", "tenantDbInitSqlTrusted"),
                     ("tenantPoolInitSql", "tenantPoolInitTrusted", "tenantPoolInitSqlTrusted"))
# The manager's extension policy (quack-on-demand.nodeExtensions, spec decision 17), assignment key
# -> the variable the spawn script reads. Always all three, from the assignment only.
_NODE_EXTENSION_ENV = (("allowCommunity", "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS"),
                       ("allowUnsigned", "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS"),
                       ("autoinstallKnown", "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS"))
# The manager's decide limits (quack-on-demand.nodeAuthzLimits), assignment key -> the variable
# qod-node reads. From the assignment only; an older manager sends none, and a value that is not a
# JSON integer is dropped, both leaving qod-node's defaults (it refuses to start on a malformed one).
_NODE_AUTHZ_LIMIT_ENV = (("decideTimeoutMs", "QOD_AUTHZ_DECIDE_TIMEOUT_MS"),
                         ("cacheEntries", "QOD_AUTHZ_CACHE_ENTRIES"))
# Sensitive values never sit in the node's environment or command line (a node user with SQL reads
# both back through getenv() or /proc/<pid>/environ|cmdline): they go to a private directory, one
# file per key, that the spawn script reads and deletes. Same list as SECRET_KEYS in
# spawn-quack-node.sh and NodeSecretFiles.Keys on the manager.
_SECRET_KEYS = ("pgPassword", "encryptionKey", "extraSetupSql", "objectStoreSql", "QOD_NODE_TOKEN",
                "QOD_NODE_AUTHZ_KEY", "tenantDbInitSql", "tenantDbInitSqlTrusted", "tenantPoolInitSql",
                "tenantPoolInitSqlTrusted", "QOD_INIT_NONCE", "QOD_S3_ACCESS_KEY_ID",
                "QOD_S3_SECRET_ACCESS_KEY", "QOD_AZURE_CONNECTION_STRING", "QOD_GCS_KEY_ID", "QOD_GCS_SECRET")
_CLOUD_CRED_KEYS = _SECRET_KEYS[11:]
# An assignment's env is a tenant-db's metastore, tenant-admin input: only the keys the spawn script
# reads from its environment are laid over the node's environment, and only the metastore's own
# secrets below become files. Any other name (BASH_ENV, which bash expands before running the
# script, PATH, QOD_NODE_BIN...) would run commands on this server or replace what this
# process sets. The manager filters too; this is defense in depth against an older manager or a
# manager bug. Same set as TenantDb.NodeEnvKeys on the manager (NodeSecretFilesSpec pins it).
_NODE_ENV_KEYS = ("pgHost", "pgPort", "pgUser", "dbName", "catalogAlias", "schemaName", "dataPath", "encrypted")
# The metastore's own secrets, taken from an assignment's env in any spelling: same set as
# TenantDb.NodeSecretEnvKeys (NodeSecretFilesSpec pins it).
_METASTORE_SECRET_KEYS = ("pgPassword", "encryptionKey")
SECRETS_DIR = "secrets"


def _canonical_secret(name: str) -> str | None:
    lower = name.lower()
    return next((k for k in _SECRET_KEYS if k.lower() == lower), None)


def write_secrets_dir(parent: Path, values: dict[str, str]) -> Path:
    """A fresh directory under `parent`, 0700, holding one 0600 file per value. Removed again if
    any write fails."""
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(parent, 0o700)
    d = Path(tempfile.mkdtemp(prefix="node-", dir=parent))
    try:
        for key, value in values.items():
            if _canonical_secret(key) != key:
                raise ValueError(f"not a node secret key: {key}")
            fd = os.open(d / key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
                f.write(value)
    except BaseException:
        shutil.rmtree(d, ignore_errors=True)
        raise
    return d


def _no_advertise_host(reason: str) -> SystemExit:
    sys.stderr.write(f"qod fleet join: cannot pick an advertise host ({reason}); pass --advertise-host "
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


CGROUP_ROOT = Path("/sys/fs/cgroup")


def _read_text(path: Path) -> str | None:
    try:
        return path.read_text().strip()
    except OSError:
        return None


def cgroup_memory_limit(root: Path = CGROUP_ROOT) -> int | None:
    """Container memory limit in bytes (cgroup v2, else v1); None when unlimited or unknown."""
    for f in (root / "memory.max", root / "memory" / "memory.limit_in_bytes"):
        raw = _read_text(f)
        if raw is None:
            continue
        try:
            value = int(raw)
        except ValueError:  # "max" or garbage: no limit we can use
            return None
        return value if value > 0 else None
    return None


def cgroup_cpu_limit(root: Path = CGROUP_ROOT) -> int | None:
    """Container CPU quota in whole cores, rounded up, at least 1 (cgroup v2, else v1); None when
    unlimited or unknown."""
    raw = _read_text(root / "cpu.max")
    if raw is not None:
        parts = raw.split()
        if len(parts) != 2:
            return None
        quota, period = parts
    else:
        quota = _read_text(root / "cpu" / "cpu.cfs_quota_us")
        period = _read_text(root / "cpu" / "cpu.cfs_period_us")
        if quota is None or period is None:
            return None
    try:
        q, p = int(quota), int(period)
    except ValueError:  # quota "max"
        return None
    if q <= 0 or p <= 0:  # v1 quota -1 = unlimited
        return None
    return max(1, math.ceil(q / p))


def host_capacity(root: Path = CGROUP_ROOT) -> tuple[int | None, int | None]:
    """(logical cores, RAM bytes) this process may use: the host's, capped by a container's cgroup
    limits so the manager's memory-fit claim sees `docker run --memory / --cpus`. Either is None
    when the platform will not say."""
    cpus = os.cpu_count()
    try:
        mem = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (ValueError, OSError, AttributeError):
        mem = None
    cpu_limit, mem_limit = cgroup_cpu_limit(root), cgroup_memory_limit(root)
    if cpu_limit is not None:
        cpus = cpu_limit if cpus is None else min(cpus, cpu_limit)
    if mem_limit is not None:
        mem = mem_limit if mem is None else min(mem, mem_limit)
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
    mis-addressed or mis-tokened join process is diagnosable from its log; the raw body otherwise."""
    try:
        body = r.json()
    except (ValueError, TypeError, AttributeError):
        body = None
    if isinstance(body, dict) and body.get("error"):
        return f"{body['error']}: {body.get('message', '')}"
    return getattr(r, "text", "") or ""


def _body_of(r) -> dict:
    try:
        body = r.json()
    except (ValueError, TypeError, AttributeError):
        return {}
    return body if isinstance(body, dict) else {}


def refusal_lines(r, *, name: str, address: str) -> list[str]:
    """The fix for a heartbeat the manager rejected: the manager's own `to fix` block, or this CLI's
    hint for a manager that sends no actions."""
    body = _body_of(r)
    lines = action_lines(body.get("actions"), server="this server")
    if lines:
        return lines
    hint = status_hint(r.status_code, body.get("error"), name=name, address=address)
    return [f"hint: {hint}"] if hint else []


def permanent_refusal(r) -> bool:
    """A refusal no retry can fix: the manager says so (retryable: false), or this URL has no fleet
    endpoint (404, or the 401 of a manager that is not in fleet mode, which carries no code)."""
    body = _body_of(r)
    if body.get("retryable") is False:
        return True
    return r.status_code == 404 or (r.status_code == 401 and not body.get("error"))


def _causes(exc: BaseException) -> list[BaseException]:
    """exc and its __cause__/__context__ chain: httpx wraps the socket or ssl error."""
    seen: list[BaseException] = []
    while exc is not None and exc not in seen:
        seen.append(exc)
        exc = exc.__cause__ or exc.__context__
    return seen


def scheme_mismatch(exc: BaseException, manager_url: str) -> bool:
    """An https:// URL whose port answered plain HTTP: no retry can fix that, only another URL."""
    return (httpx.URL(manager_url).scheme == "https"
            and any("WRONG_VERSION_NUMBER" in str(e) for e in _causes(exc)))


def network_hint(exc: BaseException, manager_url: str) -> str | None:
    """A likely fix for a heartbeat that never reached the manager, or None when unrecognised."""
    url = httpx.URL(manager_url)
    host, port = url.host, url.port or (443 if url.scheme == "https" else 80)
    chain = _causes(exc)
    text = " ".join(str(e) for e in chain)
    if scheme_mismatch(exc, manager_url):
        plain = str(url.copy_with(scheme="http")).rstrip("/")
        return (f"{host}:{port} answered plain HTTP to a TLS handshake (the manager's REST port has "
                f"no TLS of its own). Use --manager {plain} --insecure")
    if "CERTIFICATE_VERIFY_FAILED" in text:
        return (f"the certificate presented by {host}:{port} is not trusted by this host (self-signed, "
                "or issued for another name); use a certificate this host trusts, or a host name it covers")
    if any(isinstance(e, socket.gaierror) for e in chain) or any(
        m in text for m in ("nodename nor servname", "Name or service not known",
                            "Temporary failure in name resolution", "getaddrinfo failed")):
        return (f"host '{host}' does not resolve from this machine; check the --manager host name "
                "(or QOD_MANAGER_URL), or use the manager's IP address")
    if any(isinstance(e, ConnectionRefusedError) for e in chain) or "Connection refused" in text:
        return (f"nothing listens on {host}:{port}; check the manager is running and that {port} is its "
                "REST port (default 20900)")
    if any(isinstance(e, httpx.TimeoutException) for e in chain):
        return (f"no answer from {host}:{port} within 10s; check the network route and any firewall "
                "between this server and the manager")
    return None


def status_hint(status: int, code: str | None, *, name: str, address: str) -> str | None:
    """A likely fix for a heartbeat the manager rejected, or None when unrecognised."""
    if code == "address_change_refused":
        return (f"server '{name}' is registered with another address than {address}. Either run "
                f"`qod fleet join` with a different --name, or on the manager run `qod fleet drain {name}` (then "
                f"`qod fleet undrain {name}` once it reconnects; the move resets its approval, so outside "
                f"QOD_FLEET_AUTO_APPROVE also run `qod fleet approve {name}`) or `qod fleet remove {name}`")
    if code == "source_change_refused":
        return (f"server '{name}' is approved from another address than this machine's. If this is a "
                f"legitimate move, on the manager: `qod fleet drain {name}`, let it re-join, "
                f"`qod fleet approve {name}` once it shows as pending, then `qod fleet undrain {name}`; "
                f"otherwise another machine is using this name.")
    if code == "approval_unbound":
        return ("this server was approved before its address was recorded, and its heartbeats come from "
                f"outside QOD_FLEET_AUTO_APPROVE. On the manager: `qod fleet drain {name}`, then "
                f"`qod fleet approve {name}` once it shows as pending, then `qod fleet undrain {name}` "
                "(or add this server's network to QOD_FLEET_AUTO_APPROVE)")
    if code == "fleet_unauthorized":
        return ("the join token does not match the manager's; set QOD_FLEET_JOIN_TOKEN to the value "
                "the manager was started with")
    if code == "fleet_disabled" or (status == 401 and code is None):
        return ("the manager is not running in fleet mode; start it with QOD_RUNTIME_TYPE=fleet and "
                "QOD_FLEET_JOIN_TOKEN, or check --manager points at the fleet manager")
    if status == 404:
        return ("no fleet heartbeat endpoint at this URL; check --manager is the manager's REST address "
                "(default port 20900) and that the manager is recent enough to support fleet mode")
    if code == "backend_error":
        return "the manager could not reach its control-plane database; see the manager log (retrying)"
    return None


class _Node:
    def __init__(self, assignment: dict, proc, started_at: float, secrets_dir: Path | None = None):
        self.assignment, self.proc, self.started_at = assignment, proc, started_at
        # The script deletes it after reading; the join process removes it too once the node is
        # up, failed or stopped, so a script that died before reading cannot leave it behind.
        self.secrets_dir = secrets_dir
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

    def drop_secrets(self) -> None:
        if self.secrets_dir is not None:
            shutil.rmtree(self.secrets_dir, ignore_errors=True)

    def exit_error(self) -> str:
        # The process is gone: give the drain thread a moment to read the last lines it wrote.
        if self.drainer is not None:
            self.drainer.join(DRAIN_JOIN_S)
        return f"exited with {self.proc.poll()}: {self.tail()}"


class FleetMember:
    def __init__(self, manager_url: str, join_token: str, *, name: str, advertise_host: str, bind_host: str,
                 node_port: int, spawn_script: Path, node_bin: Path | None, state_dir: Path, insecure: bool,
                 http=httpx, popen=subprocess.Popen, sleep: Callable[[float], None] = time.sleep,
                 port_open: Callable[[int], bool] | None = None, clock: Callable[[], float] = time.monotonic,
                 capacity: Callable[[], tuple[int | None, int | None]] = host_capacity,
                 duckdb_version: str | None = None):
        if manager_url.lower().startswith("http://") and not insecure:
            sys.stderr.write("qod fleet join: refusing a plain http:// manager URL (the assignment carries credentials); pass --insecure to override\n")
            raise SystemExit(2)
        self.manager_url, self.join_token = manager_url.rstrip("/"), join_token
        self.name, self.advertise_host, self.bind_host, self.node_port = name, advertise_host, bind_host, node_port
        self.spawn_script, self.node_bin, self.state_dir = spawn_script, node_bin, Path(state_dir)
        self.http, self.popen, self.sleep, self.clock, self.capacity = http, popen, sleep, clock, capacity
        probe_host = "127.0.0.1" if bind_host == "0.0.0.0" else bind_host
        self.port_open = port_open or (lambda p: _port_open(probe_host, p))
        self.node: _Node | None = None
        self.last_report: dict = {"assignmentEpoch": 0, "nodeId": None, "state": "none", "pid": None, "error": None, "startedAt": None, "action": None}
        self.duckdb_version = duckdb_version
        self.failures = 0
        # None until the first successful heartbeat, then False while heartbeats fail: drives the
        # one-line "connected" / "reconnected" status, never repeated on every heartbeat.
        self.connected: bool | None = None
        # The last failed-heartbeat line, so its hint is printed once rather than every retry.
        self.last_failure: str | None = None
        # How many heartbeats in a row failed with last_failure, for the one-line reminder.
        self.repeats = 0
        # The manager's last approval answer (approved | pending), so the line prints on change only.
        self.approval: str | None = None
        # Monotonic deadline of the scheduled restart of a failed node; None while none is scheduled.
        self.next_restart_at: float | None = None
        # (pool, key) of each assignment env key already named as dropped, so it is named once.
        self.dropped_keys: set[tuple[str, str]] = set()
        self.state_dir.mkdir(parents=True, exist_ok=True)

    # ---- pidfile ----
    @property
    def pidfile(self) -> Path:
        return self.state_dir / PIDFILE

    def reap_orphan(self) -> int | None:
        """Kill a node left behind by a previous join process, identified by the pidfile and a command
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
        sys.stderr.write(f"qod fleet join: reaping orphan node pid {pid} from a previous join process\n")
        # TERM the script so its trap stops duckdb and removes the node and spill directories; if
        # it lingers, KILL its whole process group (it leads its own session), which takes the
        # duckdb grandchild too.
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
        if n.state != "starting":
            n.drop_secrets()
        return {"assignmentEpoch": n.assignment["epoch"], "nodeId": n.assignment["nodeId"], "state": n.state,
                "pid": n.proc.pid if alive else None, "error": n.error,
                "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(n.wall_started_at)),
                "action": node_failure_action(n.error, n.assignment["port"]) if n.state == "failed" else None}

    # ---- process control ----
    def _warn_dropped(self, assignment: dict, keys: list[str]) -> None:
        """Name each assignment env key that reaches no node, once per pool and key, never its value."""
        pool = assignment.get("poolKey") or {}
        pool_id = "/".join(str(pool.get(p, "?")) for p in ("tenant", "tenantDb", "pool"))
        for k in sorted(keys):
            if (pool_id, k) not in self.dropped_keys:
                self.dropped_keys.add((pool_id, k))
                sys.stderr.write(f"qod fleet join: WARN ignoring {k!r} in the assignment env for {pool_id}: a node "
                                 f"gets only {', '.join(_NODE_ENV_KEYS)} and the metastore's own secrets\n")

    def _launch_spec(self, assignment: dict) -> tuple[list[str], dict[str, str], dict[str, str]]:
        """Command, environment and secret files for `assignment`. Raises KeyError/TypeError on a
        malformed assignment, before anything is stopped."""
        env = {k: v for k, v in os.environ.items()
               if (k in _ENV_PASSTHROUGH or k.startswith(_ENV_PASSTHROUGH_PREFIXES))
               and _canonical_secret(k) is None}
        secrets = {k: os.environ[k] for k in _CLOUD_CRED_KEYS if os.environ.get(k)}
        dropped = []
        for k, v in assignment["env"].items():
            k = str(k)
            canonical = _canonical_secret(k)
            if k in _NODE_ENV_KEYS:
                env[k] = str(v)
            elif canonical in _METASTORE_SECRET_KEYS:
                if canonical == k or canonical not in secrets:
                    # The exact spelling wins over a differently-cased one.
                    secrets[canonical] = str(v)
            else:
                dropped.append(k)
        self._warn_dropped(assignment, dropped)
        env["kind"] = str(assignment["kind"])
        env["QOD_NODE_BIND"] = self.bind_host
        # Always from the assignment, empty when absent: a stray value in this process's environment
        # (a resourceSql=... exported by hand) must never reach a node.
        for key in _SQL_KEYS:
            env[key] = str(assignment.get(key) or "")
        for key in ("extraSetupSql", "objectStoreSql"):
            if assignment.get(key):
                secrets[key] = str(assignment[key])
        for key, trusted, trusted_file in _TENANT_INIT_KEYS:
            if assignment.get(key):
                secrets[trusted_file if assignment.get(trusted) is True else key] = str(assignment[key])
        # A fresh init marker nonce per start: the spawn script marks the tenant's sections with it,
        # and qod-node honours only marker lines carrying it.
        secrets["QOD_INIT_NONCE"] = _token_hex(16)
        secrets["QOD_NODE_TOKEN"] = str(assignment["token"])
        secrets["QOD_NODE_AUTHZ_KEY"] = str(assignment["authzKey"])
        env["QOD_NODE_ID"] = str(assignment["nodeId"])
        # On only for a JSON true; an older manager sends no policy, which leaves all three off.
        extensions = assignment.get("nodeExtensions") or {}
        for key, name in _NODE_EXTENSION_ENV:
            env[name] = "true" if extensions.get(key) is True else "false"
        limits = assignment.get("authzLimits")
        for key, name in _NODE_AUTHZ_LIMIT_ENV:
            value = limits.get(key) if isinstance(limits, dict) else None
            if isinstance(value, int) and not isinstance(value, bool):
                env[name] = str(value)
        if self.node_bin is not None:
            env["QOD_NODE_BIN"] = str(self.node_bin)
        env["QOD_SECRETS_DIR_EPHEMERAL"] = "1"
        cmd = ["bash", str(self.spawn_script), str(int(assignment["port"]))]
        return cmd, env, {k: v for k, v in secrets.items() if v}

    @property
    def secrets_root(self) -> Path:
        return self.state_dir / SECRETS_DIR

    def _start(self, assignment: dict,
               spec: tuple[list[str], dict[str, str], dict[str, str]] | None = None) -> None:
        cmd, env, secrets = spec or self._launch_spec(assignment)
        secrets_dir = write_secrets_dir(self.secrets_root, secrets)
        env = dict(env, QOD_SECRETS_DIR=str(secrets_dir))
        # Own session: a manager-side stop reaches the node through this process, never through a
        # terminal signal. The pidfile is what lets the NEXT join process find it if this one dies.
        try:
            proc = self.popen(cmd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE, start_new_session=True)
        except BaseException:
            shutil.rmtree(secrets_dir, ignore_errors=True)
            raise
        # Track the child before any fallible bookkeeping: an untracked child would be spawned
        # again on the next heartbeat. A missing pidfile only degrades orphan reaping after a
        # join-process crash, so a write failure is a warning, not a failed start.
        self.node = _Node(assignment, proc, self.clock(), secrets_dir)
        try:
            self.pidfile.write_text(f"{proc.pid}\n")
        except OSError as exc:
            sys.stderr.write(f"qod fleet join: WARN could not write pidfile {self.pidfile}: {exc}; "
                             f"a crash of this process would leave node pid {proc.pid} unreaped\n")

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
        n.drop_secrets()
        self.last_report = {"assignmentEpoch": n.assignment["epoch"], "nodeId": n.assignment["nodeId"],
                            "state": "stopped", "pid": None, "error": None, "startedAt": None, "action": None}
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
                sys.stderr.write(f"qod fleet join: node failed ({n.error}); restarting in {delay}s\n")
                action = node_failure_action(n.error, n.assignment["port"])
                if action:
                    sys.stderr.write(f"qod fleet join:   to fix: {action}\n")
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
                "qodVersion": __version__, "os": f"{sys.platform}-{platform.machine()}",
                "duckdbVersion": self.duckdb_version, "cpus": cpus, "memoryBytes": mem, "node": self._report()}
        try:
            r = self.http.post(f"{self.manager_url}/api/fleet/heartbeat", json=body,
                               headers={"X-Fleet-Token": self.join_token}, timeout=10.0)
        except Exception as exc:  # network: keep the node running, retry later
            hint = network_hint(exc, self.manager_url)
            if hint:
                message, detail = f"cannot reach the manager at {self.manager_url}: {hint}", [f"(cause: {exc})"]
            else:
                message, detail = f"heartbeat to {self.manager_url} failed: {exc}", []
            self._report_failure(message, detail, key=type(exc).__name__)
            # A wrong scheme before the first contact is a misconfigured --manager: stop rather
            # than retry forever. Once connected, a node may be running, so keep retrying.
            if self.connected is None and scheme_mismatch(exc, self.manager_url):
                raise SystemExit(2)
            return BACKOFF_MIN_S
        if not r.is_success:
            self._report_failure(f"WARN manager answered {r.status_code}: {_error_of(r)}",
                                 refusal_lines(r, name=self.name, address=f"{self.advertise_host}:{self.node_port}"),
                                 key=_body_of(r).get("error") or str(r.status_code))
            # Same rule for a refusal the manager marks permanent (wrong token, wrong URL, a name
            # registered elsewhere): before the first contact it is a misconfiguration.
            if self.connected is None and permanent_refusal(r):
                raise SystemExit(2)
            return BACKOFF_MIN_S
        self.last_failure = None
        if not self.connected:
            verb = "connected" if self.connected is None else "reconnected"
            sys.stderr.write(f"qod fleet join: {verb} to manager {self.manager_url} as server '{self.name}'\n")
            self.connected = True
        # One bad reply (not JSON, a malformed assignment) or a failed spawn/pidfile write must not
        # kill the process: log it, keep whatever node runs, and heartbeat again after the backoff.
        try:
            reply = r.json()
            self._note_approval(reply.get("approval"))
            self._reconcile(reply.get("assignment"))
            return float(reply.get("heartbeatSec", 5))
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as exc:
            sys.stderr.write(f"qod fleet join: could not act on the manager's reply: {exc!r}\n")
            return BACKOFF_MIN_S

    def _report_failure(self, message: str, detail: list[str], key: str) -> None:
        """The full failure (message, then its fix) once per distinct failure, then one short line
        per retry while it repeats, so a refused join does not flood its log every 5 s."""
        if message != self.last_failure:
            sys.stderr.write(f"qod fleet join: {message}\n")
            for line in detail:
                sys.stderr.write(f"qod fleet join:   {line}\n")
            self.repeats = 1
        else:
            self.repeats += 1
            sys.stderr.write(f"qod fleet join: still failing ({key}, {self.repeats} times), retrying\n")
        self.last_failure = message
        self._mark_disconnected()

    def _mark_disconnected(self) -> None:
        if self.connected:
            self.connected = False

    def _note_approval(self, approval: str | None) -> None:
        """One line when the manager's approval of this server changes. None (a manager predating
        approval) and a first `approved` print nothing: the connected line already said enough."""
        if approval is None or approval == self.approval:
            return
        if approval == "pending":
            sys.stderr.write(f"qod fleet join: server '{self.name}' is waiting for approval on the manager\n")
            sys.stderr.write(f"qod fleet join:   hint: an admin approves it with `qod fleet approve {self.name}`, "
                             "or adds this server's address to QOD_FLEET_AUTO_APPROVE on the manager\n")
        elif approval == "approved" and self.approval == "pending":
            sys.stderr.write(f"qod fleet join: server '{self.name}' approved\n")
        self.approval = approval

    def sweep_secrets(self) -> None:
        """Remove secret directories a previous join process left behind (killed between writing one
        and its script reading it). Only called while no node of this process runs."""
        shutil.rmtree(self.secrets_root, ignore_errors=True)

    def run_forever(self) -> None:
        self.reap_orphan()
        self.sweep_secrets()
        sys.stderr.write(f"qod fleet join: server '{self.name}', advertise host {self.advertise_host}, node binds "
                         f"{self.bind_host}:{self.node_port} (override with --advertise-host / --bind-host if "
                         f"this is not the data interface)\n")
        try:
            while True:
                self.sleep(self.run_once())
        finally:
            self._stop()
