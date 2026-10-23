import io
import os
import pathlib
import signal
import stat
import subprocess
import sys

import httpx
import pytest

from qod_cli.fleet_join import (
    BACKOFF_MIN_S,
    FleetMember,
    cgroup_cpu_limit,
    cgroup_memory_limit,
    host_capacity,
    network_hint,
    node_failure_action,
    status_hint,
)

# qod fleet join is POSIX-only (Linux, macOS): it relies on process groups (os.killpg) and
# start_new_session. The typer wrapper refuses to run on Windows, and so do these tests.
pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="qod fleet join is POSIX-only")


class FakeResponse:
    def __init__(self, status, body):
        self.status_code, self._body = status, body
        self.is_success = 200 <= status < 300
        self.text = ""
    def json(self):
        return self._body


class FakeHttp:
    """Scripted manager: each post pops the next reply and records the request."""
    def __init__(self, replies):
        self.replies, self.requests = list(replies), []
    def post(self, url, json=None, headers=None, timeout=None):
        self.requests.append((url, json, headers))
        return self.replies.pop(0)


class FakeProc:
    def __init__(self, pid=4242, alive=True, stuck=False):
        self.pid, self._alive, self.terminated, self.killed = pid, alive, False, False
        self.stuck = stuck  # ignores SIGTERM: wait(timeout) expires
        self.stderr = io.BytesIO(b"boot\n")
    def poll(self):
        return None if self._alive else 1
    def terminate(self):
        self.terminated = True
        if not self.stuck:
            self._alive = False
    def kill(self):
        self.killed = True; self._alive = False
    def wait(self, timeout=None):
        if self._alive and timeout is not None:
            raise subprocess.TimeoutExpired("spawn", timeout)
        self._alive = False
        return 0


@pytest.fixture(autouse=True)
def recorded_signals(monkeypatch):
    """Never signal a real process from a test: FakeProc pids are arbitrary numbers."""
    sent = []
    monkeypatch.setattr("qod_cli.fleet_join.os.kill", lambda pid, sig: sent.append(("kill", pid, sig)))
    monkeypatch.setattr("qod_cli.fleet_join.os.killpg", lambda pgid, sig: sent.append(("killpg", pgid, sig)), raising=False)
    return sent


def assignment(epoch, node_id="quack-acme-db-bi-1", port=21900):
    return {"epoch": epoch, "nodeId": node_id, "poolKey": {"tenant": "acme", "tenantDb": "db", "pool": "bi"},
            "port": port, "token": "tok", "kind": "memory", "env": {"pgHost": "h", "pgPassword": "pw"},
            "dbInitSql": "", "objectStoreSql": "", "extraSetupSql": "", "lockdownSql": ""}


def read_secrets(env):
    """The secret files the spawn script would read, as the launcher left them at popen time."""
    d = pathlib.Path(env["QOD_SECRETS_DIR"])
    return {f.name: f.read_text() for f in d.iterdir()}


def make_member(http, popen, tmp_path, port_open=lambda p: True, **kw):
    tmp_path.mkdir(parents=True, exist_ok=True)
    spawn = tmp_path / "spawn.sh"; spawn.write_text("#!/bin/sh\nexit 0\n")
    kw.setdefault("capacity", lambda: (8, 64 << 30))
    kw.setdefault("node_bin", None)
    return FleetMember("https://mgr:20900", "secret", name="srv-1", advertise_host="10.0.0.7", bind_host="10.0.0.7",
                 node_port=21900, spawn_script=spawn, state_dir=tmp_path / "state",
                 insecure=False, http=http, popen=popen, sleep=lambda s: None, port_open=port_open, **kw)


def test_the_node_runs_the_members_qod_node(tmp_path, monkeypatch):
    # The node's binary comes from the member (the bundled install, or --qod-node-bin), never from a
    # QOD_NODE_BIN or DUCKDB_BIN this process happens to carry.
    monkeypatch.setenv("QOD_NODE_BIN", "/elsewhere/qod-node")
    monkeypatch.setenv("DUCKDB_BIN", "/elsewhere/duckdb")
    envs = []
    def popen(cmd, env=None, **kw):
        envs.append(env); return FakeProc()
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    make_member(http, popen, tmp_path, node_bin=tmp_path / "q" / "bin" / "qod-node").run_once()
    assert envs[0]["QOD_NODE_BIN"] == str(tmp_path / "q" / "bin" / "qod-node")
    assert "DUCKDB_BIN" not in envs[0]
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    envs.clear()
    make_member(http, popen, tmp_path / "other").run_once()
    assert "QOD_NODE_BIN" not in envs[0]


def test_heartbeat_carries_identity_capacity_and_no_node_initially(tmp_path):
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": None})])
    member = make_member(http, lambda *a, **k: FakeProc(), tmp_path)
    assert member.run_once() == 5
    url, body, headers = http.requests[0]
    assert url == "https://mgr:20900/api/fleet/heartbeat"
    assert headers["X-Fleet-Token"] == "secret"
    assert body["name"] == "srv-1" and body["advertiseHost"] == "10.0.0.7" and body["nodePort"] == 21900
    assert body["cpus"] == 8 and body["memoryBytes"] == 64 << 30
    assert body["node"] == {"assignmentEpoch": 0, "nodeId": None, "state": "none", "pid": None, "error": None, "startedAt": None, "action": None}


def test_assignment_starts_node_with_spawn_contract_and_reports_running(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        procs.append((cmd, env, kw, read_secrets(env))); return FakeProc()
    http = FakeHttp([
        FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(3)}),
        FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(3)}),
    ])
    member = make_member(http, popen, tmp_path)
    member.run_once()
    cmd, env, kw, secrets = procs[0]
    assert cmd[-1] == "21900" and env["kind"] == "memory"
    assert secrets == {"pgPassword": "pw", "QOD_NODE_TOKEN": "tok"}
    assert env["QOD_NODE_BIND"] == "10.0.0.7" and env["QOD_SECRETS_DIR_EPHEMERAL"] == "1"
    assert kw["start_new_session"] is True
    assert (tmp_path / "state" / "node.pid").read_text().strip() == "4242"
    member.run_once()
    node = http.requests[1][1]["node"]
    assert node["state"] == "running" and node["assignmentEpoch"] == 3 and node["nodeId"] == "quack-acme-db-bi-1"


def test_epoch_change_restarts_and_null_assignment_stops_and_clears_pidfile(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=100 + len(procs)); procs.append(p); return p
    http = FakeHttp([
        FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
        FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(2)}),
        FakeResponse(200, {"heartbeatSec": 5, "assignment": None}),
        FakeResponse(200, {"heartbeatSec": 5, "assignment": None}),
    ])
    member = make_member(http, popen, tmp_path)
    member.run_once(); member.run_once()
    assert procs[0].terminated and len(procs) == 2
    member.run_once()
    assert procs[1].terminated
    assert not (tmp_path / "state" / "node.pid").exists()
    member.run_once()
    assert http.requests[3][1]["node"]["state"] == "stopped"


def test_new_token_or_node_id_under_the_same_epoch_restarts_the_node(tmp_path):
    # A server removed while unreachable rejoins with its epoch reset: its next claim is epoch 1
    # again, for the same or another node id, always with a new token. The old process must go.
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=100 + len(procs)); procs.append(p); p.secrets = read_secrets(env); return p
    first = assignment(1)
    same = dict(first)
    new_token = dict(first, token="tok-2")
    new_node = dict(new_token, nodeId="quack-acme-db-bi-7")
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": a})
                     for a in (first, same, new_token, new_node)])
    member = make_member(http, popen, tmp_path)
    member.run_once(); member.run_once()
    assert len(procs) == 1 and not procs[0].terminated  # an identical assignment keeps the node
    member.run_once()
    assert procs[0].terminated and len(procs) == 2
    assert procs[1].secrets["QOD_NODE_TOKEN"] == "tok-2"
    member.run_once()
    assert procs[1].terminated and len(procs) == 3
    assert member.node.assignment["nodeId"] == "quack-acme-db-bi-7"


def test_pidfile_write_failure_keeps_the_spawned_node_tracked(tmp_path, monkeypatch, capsys):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=100 + len(procs)); procs.append(p); return p
    http = FakeHttp([
        FakeResponse(200, {"heartbeatSec": 7, "assignment": assignment(1)}),
        FakeResponse(200, {"heartbeatSec": 7, "assignment": assignment(1)}),
        FakeResponse(200, {"heartbeatSec": 7, "assignment": None}),
    ])
    member = make_member(http, popen, tmp_path)
    def no_space(self, *a, **k):
        raise OSError(28, "No space left on device")
    monkeypatch.setattr(type(member.pidfile), "write_text", no_space)
    assert member.run_once() == 7  # the spawn succeeded: a normal heartbeat, not the backoff
    assert len(procs) == 1 and member.node is not None and member.node.proc is procs[0]
    assert "WARN" in capsys.readouterr().err
    member.run_once()
    assert len(procs) == 1  # no second spawn for the same assignment
    assert http.requests[1][1]["node"]["state"] == "running"
    member.run_once()
    assert procs[0].terminated and member.node is None


def test_crash_is_restarted_with_backoff_and_reported_failed(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=200 + len(procs), alive=False); procs.append(p); return p
    replies = [FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}) for _ in range(4)]
    http = FakeHttp(replies)
    member = make_member(http, popen, tmp_path, port_open=lambda p: False)
    member.run_once()            # start #1, dies at once
    member.run_once()            # reports failed, schedules restart with backoff
    assert http.requests[1][1]["node"]["state"] == "failed"
    assert "boot" in http.requests[1][1]["node"]["error"]
    n_before = len(procs)
    member.run_once()            # inside backoff: no new spawn
    assert len(procs) == n_before
    member.clock = lambda: member.next_restart_at + 1  # fast-forward
    member.run_once()
    assert len(procs) == n_before + 1


def test_reap_orphan_kills_recorded_pid_only_when_it_is_a_spawn_script(tmp_path, monkeypatch):
    state = tmp_path / "state"; state.mkdir()
    (state / "node.pid").write_text("31337")
    killed = []
    monkeypatch.setattr("qod_cli.fleet_join._cmdline", lambda pid: "bash /x/spawn-quack-node.sh 21900 tok" if pid == 31337 else "")
    monkeypatch.setattr("qod_cli.fleet_join.os.kill", lambda pid, sig: killed.append((pid, sig)))
    member = make_member(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    assert member.reap_orphan() == 31337
    assert killed and killed[0][0] == 31337
    assert not (state / "node.pid").exists()
    # a recycled pid that is not our script is left alone
    (state / "node.pid").write_text("31337")
    monkeypatch.setattr("qod_cli.fleet_join._cmdline", lambda pid: "postgres: checkpointer")
    killed.clear()
    assert member.reap_orphan() is None and killed == []
    assert not (state / "node.pid").exists()


def test_refuses_plain_http_without_insecure(tmp_path):
    with pytest.raises(SystemExit):
        FleetMember("http://mgr:20900", "s", name="a", advertise_host="h", bind_host="h", node_port=1,
              spawn_script=tmp_path / "x", node_bin=None, state_dir=tmp_path, insecure=False)
    FleetMember("http://mgr:20900", "s", name="a", advertise_host="h", bind_host="h", node_port=1,
          spawn_script=tmp_path / "x", node_bin=None, state_dir=tmp_path, insecure=True)


def test_node_env_is_an_allowlist_and_sql_keys_come_only_from_the_assignment(tmp_path, monkeypatch):
    monkeypatch.setenv("QOD_FLEET_JOIN_TOKEN", "fleet-wide-secret")
    monkeypatch.setenv("lockdownSql", "X")
    monkeypatch.setenv("SOME_RANDOM_SECRET", "nope")
    monkeypatch.setenv("QOD_S3_ENDPOINT", "minio:9000")
    envs = []
    def popen(cmd, env=None, **kw):
        envs.append(env); return FakeProc()
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    make_member(http, popen, tmp_path).run_once()
    env = envs[0]
    assert "QOD_FLEET_JOIN_TOKEN" not in env and "SOME_RANDOM_SECRET" not in env
    assert "PATH" in env and env["QOD_S3_ENDPOINT"] == "minio:9000"
    assert env["lockdownSql"] == "" and env["dbInitSql"] == ""
    assert env["kind"] == "memory" and env["QOD_NODE_BIND"] == "10.0.0.7"


SECRET_VALUES = {"pw", "tok", "hunter2", "fed-sql-secret", "store-sql-secret", "s3-key-id", "s3-secret",
                 "az-conn"}


def secret_assignment():
    return dict(assignment(1), env={"pgHost": "h", "pgPassword": "hunter2", "encryptionKey": "k3y"},
                extraSetupSql="fed-sql-secret", objectStoreSql="store-sql-secret", token="tok")


def test_no_secret_value_reaches_the_node_env_or_command_line(tmp_path, monkeypatch):
    monkeypatch.setenv("QOD_S3_ACCESS_KEY_ID", "s3-key-id")
    monkeypatch.setenv("QOD_S3_SECRET_ACCESS_KEY", "s3-secret")
    monkeypatch.setenv("QOD_AZURE_CONNECTION_STRING", "az-conn")
    monkeypatch.setenv("QOD_S3_ENDPOINT", "minio:9000")
    seen = []
    def popen(cmd, env=None, **kw):
        d = pathlib.Path(env["QOD_SECRETS_DIR"])
        seen.append((cmd, env, read_secrets(env), stat.S_IMODE(d.stat().st_mode),
                     {f.name: stat.S_IMODE(f.stat().st_mode) for f in d.iterdir()}))
        return FakeProc()
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": secret_assignment()})])
    make_member(http, popen, tmp_path).run_once()
    cmd, env, secrets, dir_mode, file_modes = seen[0]
    for value in SECRET_VALUES | {"k3y"}:
        assert value not in env.values() and value not in cmd, value
        assert not any(value in v for v in env.values()), value
    assert secrets == {"pgPassword": "hunter2", "encryptionKey": "k3y", "extraSetupSql": "fed-sql-secret",
                       "objectStoreSql": "store-sql-secret", "QOD_NODE_TOKEN": "tok",
                       "QOD_S3_ACCESS_KEY_ID": "s3-key-id", "QOD_S3_SECRET_ACCESS_KEY": "s3-secret",
                       "QOD_AZURE_CONNECTION_STRING": "az-conn"}
    assert env["QOD_S3_ENDPOINT"] == "minio:9000"
    assert dir_mode == 0o700 and set(file_modes.values()) == {0o600}


def test_secrets_dir_is_removed_once_the_node_is_up_and_on_stop(tmp_path):
    dirs = []
    def popen(cmd, env=None, **kw):
        dirs.append(pathlib.Path(env["QOD_SECRETS_DIR"])); return FakeProc()
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": a}) for a in
                     (assignment(1), assignment(1), assignment(2), None)])
    member = make_member(http, popen, tmp_path, port_open=lambda p: False)
    member.run_once()
    assert dirs[0].is_dir()  # still starting: the script may not have read it yet
    member.port_open = lambda p: True
    member.run_once()
    assert not dirs[0].exists()  # running: the script has read it
    member.port_open = lambda p: False
    member.run_once()
    assert dirs[1].is_dir()
    member.run_once()  # stopped before it came up
    assert not dirs[1].exists()


def test_secrets_dir_is_removed_when_popen_fails(tmp_path):
    def popen(cmd, env=None, **kw):
        raise OSError("no bash")
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    member = make_member(http, popen, tmp_path)
    member.run_once()
    root = tmp_path / "state" / "secrets"
    assert not root.exists() or list(root.iterdir()) == []


def test_stale_secrets_dirs_are_swept_at_startup(tmp_path):
    member = make_member(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    stale = tmp_path / "state" / "secrets" / "node-old"
    stale.mkdir(parents=True)
    (stale / "pgPassword").write_text("hunter2")
    member.sweep_secrets()
    assert not stale.exists()


def test_the_spawn_script_reads_then_deletes_the_secrets_dir(tmp_path):
    # Runs the real bundled script in dry-run mode: the password comes from its file, the token stays
    # out of the init SQL (qod-node reads it itself), the directory is gone afterwards, and a value
    # left in the environment is ignored.
    from qod_cli.fleet_join import write_secrets_dir
    script = pathlib.Path(__file__).resolve().parents[1] / "src" / "qod_cli" / "scripts" / "spawn-quack-node.sh"
    d = write_secrets_dir(tmp_path / "secrets", {"pgPassword": "from-file", "QOD_NODE_TOKEN": "tok-file"})
    env = {"PATH": os.environ["PATH"], "QOD_SPAWN_DRY_RUN": "1", "kind": "ducklake", "pgHost": "h",
           "pgPort": "5432", "pgUser": "u", "dbName": "lake", "schemaName": "main",
           "dataPath": str(tmp_path / "lake"), "QOD_SECRETS_DIR": str(d), "QOD_SECRETS_DIR_EPHEMERAL": "1",
           "encryptionKey": "from-env"}
    out = subprocess.run(["bash", str(script), "21999"], env=env, capture_output=True, text=True, check=True)
    assert "PASSWORD 'from-file'" in out.stdout and "tok-file" not in out.stdout
    # The password rides a temporary secret, never an ATTACH string (duckdb_databases() prints it).
    attaches = [line for line in out.stdout.splitlines() if line.startswith("ATTACH")]
    assert attaches and all("password" not in line.lower() for line in attaches)
    assert "DROP SECRET IF EXISTS qod_catalog_pg;" in out.stdout
    assert "from-env" not in out.stdout and "ignoring encryptionKey" in out.stderr
    assert not d.exists()


def test_node_env_passes_the_spawn_scripts_admin_db_and_libpq_tls_settings(tmp_path, monkeypatch):
    # The spawn script's CREATE DATABASE runs against PG_ADMIN_DB, and a TLS metastore needs the
    # libpq PGSSL* settings: dropping them fails every node of the fleet, silently.
    passed = {"PG_ADMIN_DB": "maint", "PGSSLMODE": "verify-full", "PGSSLROOTCERT": "/etc/ca.pem",
              "PGSSLCERT": "/etc/c.pem", "PGSSLKEY": "/etc/k.pem", "PGCONNECT_TIMEOUT": "7",
              "SSL_CERT_FILE": "/etc/bundle.pem"}
    for k, v in passed.items():
        monkeypatch.setenv(k, v)
    envs = []
    def popen(cmd, env=None, **kw):
        envs.append(env); return FakeProc()
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    make_member(http, popen, tmp_path).run_once()
    assert {k: envs[0].get(k) for k in passed} == passed


def test_non_2xx_reply_logs_the_manager_error_code_at_warn(tmp_path, capsys):
    refused = FakeResponse(409, {"error": "address_change_refused", "message": "known with another address"})
    refused.text = '{"error":"address_change_refused","message":"known with another address"}'
    unauthorized = FakeResponse(401, {"error": "fleet_unauthorized", "message": "invalid token"})
    not_json = FakeResponse(502, None)
    not_json.json = lambda: (_ for _ in ()).throw(ValueError("not json"))
    not_json.text = "<html>bad gateway</html>"
    member = make_member(FakeHttp([refused, unauthorized, not_json]), lambda *a, **k: FakeProc(), tmp_path)
    assert member.run_once() == 5 and member.run_once() == 5 and member.run_once() == 5
    err = [l for l in capsys.readouterr().err.splitlines() if "hint:" not in l]
    assert "WARN" in err[0] and "409" in err[0] and "address_change_refused" in err[0]
    assert "WARN" in err[1] and "fleet_unauthorized" in err[1]
    assert "WARN" in err[2] and "502" in err[2] and "bad gateway" in err[2]


def test_logs_connected_once_then_reconnected_after_a_failure(tmp_path, capsys):
    ok = lambda: FakeResponse(200, {"heartbeatSec": 5, "assignment": None})
    class Flaky(FakeHttp):
        def post(self, url, json=None, headers=None, timeout=None):
            reply = self.replies.pop(0)
            if reply is None:
                raise httpx.ConnectError("manager down")
            return reply
    refused = FakeResponse(401, {"error": "fleet_unauthorized", "message": "invalid token"})
    member = make_member(Flaky([ok(), ok(), None, None, ok(), ok(), refused, ok()]), lambda *a, **k: FakeProc(), tmp_path)
    for _ in range(8):
        member.run_once()
    err = capsys.readouterr().err.splitlines()
    status = [l for l in err if "connected to manager" in l]
    assert len(status) == 3
    assert "qod fleet join: connected to manager https://mgr:20900" in status[0]
    assert "reconnected to manager https://mgr:20900" in status[1]
    assert "reconnected to manager https://mgr:20900" in status[2]


def test_non_json_reply_is_a_failed_heartbeat_and_keeps_the_node(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(); procs.append(p); return p
    bad = FakeResponse(200, None)
    bad.json = lambda: (_ for _ in ()).throw(ValueError("not json"))
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}), bad])
    member = make_member(http, popen, tmp_path)
    member.run_once()
    assert member.run_once() == 5
    assert not procs[0].terminated and member.node is not None


def test_malformed_assignment_is_a_failed_heartbeat_and_keeps_the_node(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(); procs.append(p); return p
    broken = assignment(2); del broken["env"]
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
                     FakeResponse(200, {"heartbeatSec": 5, "assignment": broken})])
    member = make_member(http, popen, tmp_path)
    member.run_once()
    assert member.run_once() == 5
    assert len(procs) == 1 and not procs[0].terminated
    assert member.node.assignment["epoch"] == 1


def test_network_error_keeps_the_node_running(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(); procs.append(p); return p
    class Flaky(FakeHttp):
        def post(self, url, json=None, headers=None, timeout=None):
            if self.replies:
                return super().post(url, json, headers, timeout)
            raise httpx.ConnectError("manager down")
    member = make_member(Flaky([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})]), popen, tmp_path)
    member.run_once()
    assert member.run_once() == 5
    assert not procs[0].terminated and member.node is not None


def test_popen_failure_is_a_failed_heartbeat(tmp_path):
    def popen(cmd, env=None, **kw):
        raise OSError("no bash")
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    member = make_member(http, popen, tmp_path)
    assert member.run_once() == 5 and member.node is None


def test_stop_timeout_kills_the_process_group(tmp_path, recorded_signals):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=777, stuck=True); procs.append(p); return p
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
                     FakeResponse(200, {"heartbeatSec": 5, "assignment": None})])
    member = make_member(http, popen, tmp_path)
    member.run_once(); member.run_once()
    assert procs[0].terminated
    assert ("killpg", 777, signal.SIGKILL) in recorded_signals


def test_stop_kills_the_group_even_when_the_script_already_exited(tmp_path, recorded_signals):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=888); procs.append(p); return p
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
                     FakeResponse(200, {"heartbeatSec": 5, "assignment": None})])
    member = make_member(http, popen, tmp_path)
    member.run_once()
    procs[0]._alive = False          # script exited on its own; a duckdb child may survive it
    member.run_once()
    assert not procs[0].terminated
    assert ("killpg", 888, signal.SIGKILL) in recorded_signals


def test_reap_orphan_waits_the_stop_grace_before_the_group_kill(tmp_path, monkeypatch, recorded_signals):
    state = tmp_path / "state"; state.mkdir()
    (state / "node.pid").write_text("31337")
    monkeypatch.setattr("qod_cli.fleet_join._cmdline", lambda pid: "bash spawn-quack-node.sh")
    slept = []
    member = make_member(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    member.sleep = slept.append
    assert member.reap_orphan() == 31337
    assert recorded_signals[0] == ("kill", 31337, signal.SIGTERM)
    assert recorded_signals[1] == ("kill", -31337, signal.SIGKILL)
    assert sum(slept) >= 60


def test_reap_orphan_never_signals_pid_1(tmp_path, monkeypatch, recorded_signals):
    state = tmp_path / "state"; state.mkdir()
    (state / "node.pid").write_text("1")
    monkeypatch.setattr("qod_cli.fleet_join._cmdline", lambda pid: "bash spawn-quack-node.sh")
    member = make_member(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    assert member.reap_orphan() is None and recorded_signals == []


def test_scheme_check_is_case_insensitive(tmp_path):
    with pytest.raises(SystemExit):
        FleetMember("HTTP://mgr:20900", "s", name="a", advertise_host="h", bind_host="h", node_port=1,
              spawn_script=tmp_path / "x", node_bin=None, state_dir=tmp_path, insecure=False)


def test_default_advertise_host_refuses_loopback(monkeypatch):
    import qod_cli.fleet_join as fleet_join_mod
    class Sock:
        def connect(self, addr): raise OSError("no route")
        def getsockname(self): return ("0.0.0.0", 0)
        def close(self): pass
    monkeypatch.setattr(fleet_join_mod.socket, "socket", lambda *a: Sock())
    monkeypatch.setattr(fleet_join_mod.socket, "gethostbyname", lambda h: "127.0.1.1")
    with pytest.raises(SystemExit) as e:
        fleet_join_mod.default_advertise_host()
    assert e.value.code == 2
    def gai(h): raise fleet_join_mod.socket.gaierror("unknown host")
    monkeypatch.setattr(fleet_join_mod.socket, "gethostbyname", gai)
    with pytest.raises(SystemExit):
        fleet_join_mod.default_advertise_host()


def _real_error(url):
    """The exception httpx really raises for url, so the hints match live failures."""
    try:
        httpx.post(url, timeout=2.0)
    except Exception as exc:
        return exc
    raise AssertionError(f"{url} unexpectedly answered")


@pytest.fixture
def plain_http_port():
    import http.server, threading
    srv = http.server.HTTPServer(("127.0.0.1", 0), http.server.BaseHTTPRequestHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield srv.server_address[1]
    srv.shutdown()


def test_https_to_a_plain_http_port_suggests_http_with_insecure(plain_http_port):
    url = f"https://127.0.0.1:{plain_http_port}"
    hint = network_hint(_real_error(url), url)
    assert f"--manager http://127.0.0.1:{plain_http_port} --insecure" in hint


class RaisingHttp:
    """A manager reached over the wrong scheme: every post raises the given error."""
    def __init__(self, exc, replies=()):
        self.exc, self.replies = exc, list(replies)
    def post(self, url, json=None, headers=None, timeout=None):
        if self.replies:
            return self.replies.pop(0)
        raise self.exc


def test_scheme_mismatch_before_first_contact_exits_with_the_hint(tmp_path, capsys, plain_http_port):
    url = f"https://127.0.0.1:{plain_http_port}"
    member = make_member(RaisingHttp(_real_error(url)), lambda *a, **k: FakeProc(), tmp_path)
    with pytest.raises(SystemExit) as e:
        member.run_once()
    assert e.value.code == 2
    assert "--insecure" in capsys.readouterr().err


def test_scheme_mismatch_after_contact_keeps_retrying(tmp_path, plain_http_port):
    url = f"https://127.0.0.1:{plain_http_port}"
    ok = FakeResponse(200, {"heartbeatSec": 5, "assignment": None})
    member = make_member(RaisingHttp(_real_error(url), [ok]), lambda *a, **k: FakeProc(), tmp_path)
    member.run_once()
    assert member.run_once() == BACKOFF_MIN_S


def test_unresolvable_host_names_the_host():
    url = "https://qod-no-such-host.invalid:20900"
    hint = network_hint(_real_error(url), url)
    assert "'qod-no-such-host.invalid' does not resolve" in hint


def test_refused_connection_names_the_port():
    import socket
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    url = f"http://127.0.0.1:{port}"
    assert f"nothing listens on 127.0.0.1:{port}" in network_hint(_real_error(url), url)


def test_unrecognised_network_error_has_no_hint():
    assert network_hint(RuntimeError("boom"), "https://mgr:20900") is None


@pytest.mark.parametrize("status,code,expected", [
    (409, "address_change_refused", "qod fleet drain srv-1"),
    (409, "address_change_refused", "qod fleet approve srv-1"),
    (409, "address_change_refused", "qod fleet undrain srv-1"),
    (409, "source_change_refused", "approved from another address than this machine's"),
    (409, "source_change_refused", "qod fleet drain srv-1"),
    (409, "source_change_refused", "qod fleet approve srv-1"),
    (409, "source_change_refused", "qod fleet undrain srv-1"),
    (409, "source_change_refused", "another machine is using this name"),
    (409, "approval_unbound", "approved before its address was recorded"),
    (409, "approval_unbound", "outside QOD_FLEET_AUTO_APPROVE"),
    (409, "approval_unbound", "`qod fleet drain srv-1`, then `qod fleet approve srv-1`"),
    (409, "approval_unbound", "qod fleet undrain srv-1"),
    (409, "approval_unbound", "add this server's network to QOD_FLEET_AUTO_APPROVE"),
    (401, "fleet_unauthorized", "QOD_FLEET_JOIN_TOKEN"),
    (401, None, "QOD_RUNTIME_TYPE=fleet"),
    (400, "fleet_disabled", "QOD_RUNTIME_TYPE=fleet"),
    (404, None, "REST address"),
    (502, "backend_error", "manager log"),
])
def test_rejected_heartbeat_hints(status, code, expected):
    assert expected in status_hint(status, code, name="srv-1", address="10.0.0.7:21900")


def test_hint_is_printed_once_per_distinct_failure(tmp_path, capsys):
    refused = lambda: FakeResponse(409, {"error": "address_change_refused", "message": "known"})
    ok = FakeResponse(200, {"heartbeatSec": 5, "assignment": None})
    member = make_member(FakeHttp([refused(), refused(), ok, refused()]), lambda *a, **k: FakeProc(), tmp_path)
    for _ in range(4):
        member.run_once()
    err = capsys.readouterr().err.splitlines()
    assert sum("hint:" in l for l in err) == 2
    assert "10.0.0.7:21900" in next(l for l in err if "hint:" in l)


def test_pending_approval_is_announced_once_then_approved(tmp_path, capsys):
    pending = lambda: FakeResponse(200, {"heartbeatSec": 5, "assignment": None, "approval": "pending"})
    approved = FakeResponse(200, {"heartbeatSec": 5, "assignment": None, "approval": "approved"})
    member = make_member(FakeHttp([pending(), pending(), approved]), lambda *a, **k: FakeProc(), tmp_path)
    for _ in range(3):
        member.run_once()
    err = capsys.readouterr().err.splitlines()
    assert sum("waiting for approval" in l for l in err) == 1
    assert sum("qod fleet approve srv-1" in l for l in err) == 1
    assert any("server 'srv-1' approved" in l for l in err)


def test_an_approved_or_legacy_reply_prints_no_approval_line(tmp_path, capsys):
    approved = FakeResponse(200, {"heartbeatSec": 5, "assignment": None, "approval": "approved"})
    legacy = FakeResponse(200, {"heartbeatSec": 5, "assignment": None})
    member = make_member(FakeHttp([approved, legacy]), lambda *a, **k: FakeProc(), tmp_path)
    member.run_once(); member.run_once()
    assert "approv" not in capsys.readouterr().err


def _host(monkeypatch, cpus=16, mem=64 << 30):
    monkeypatch.setattr("qod_cli.fleet_join.os.cpu_count", lambda: cpus)
    pages = {"SC_PHYS_PAGES": mem // 4096, "SC_PAGE_SIZE": 4096}
    monkeypatch.setattr("qod_cli.fleet_join.os.sysconf", lambda name: pages[name])


def test_cgroup_v2_limits_cap_the_host_values(tmp_path, monkeypatch):
    _host(monkeypatch)
    (tmp_path / "memory.max").write_text("4294967296\n")
    (tmp_path / "cpu.max").write_text("150000 100000\n")
    assert host_capacity(tmp_path) == (2, 4 << 30)


def test_cgroup_v2_max_means_unlimited(tmp_path, monkeypatch):
    _host(monkeypatch)
    (tmp_path / "memory.max").write_text("max\n")
    (tmp_path / "cpu.max").write_text("max 100000\n")
    assert host_capacity(tmp_path) == (16, 64 << 30)


def test_cgroup_v1_limits_and_unlimited_sentinels(tmp_path, monkeypatch):
    _host(monkeypatch)
    (tmp_path / "memory").mkdir()
    (tmp_path / "cpu").mkdir()
    (tmp_path / "memory" / "memory.limit_in_bytes").write_text(str(2 << 30))
    (tmp_path / "cpu" / "cpu.cfs_quota_us").write_text("400000")
    (tmp_path / "cpu" / "cpu.cfs_period_us").write_text("100000")
    assert host_capacity(tmp_path) == (4, 2 << 30)
    (tmp_path / "memory" / "memory.limit_in_bytes").write_text("9223372036854771712")
    (tmp_path / "cpu" / "cpu.cfs_quota_us").write_text("-1")
    assert host_capacity(tmp_path) == (16, 64 << 30)


def test_cgroup_garbage_or_missing_files_fall_back_to_host(tmp_path, monkeypatch):
    _host(monkeypatch)
    assert host_capacity(tmp_path) == (16, 64 << 30)
    (tmp_path / "memory.max").write_text("lots")
    (tmp_path / "cpu.max").write_text("")
    assert cgroup_memory_limit(tmp_path) is None
    assert cgroup_cpu_limit(tmp_path) is None
    assert host_capacity(tmp_path) == (16, 64 << 30)


def test_cgroup_fractional_cpu_rounds_up_to_at_least_one(tmp_path):
    (tmp_path / "cpu.max").write_text("25000 100000\n")
    assert cgroup_cpu_limit(tmp_path) == 1


class _Done:
    def __init__(self, stdout):
        self.stdout = stdout


def test_refusal_prints_the_managers_fix_instead_of_the_local_hint(tmp_path, capsys):
    body = {"error": "address_change_refused",
            "message": "server 'srv-1' is registered at 10.0.0.9:21900; this heartbeat reports 10.0.0.7:21900 (not drained, holds no node)",
            "details": {"server": "srv-1"},
            "actions": [{"where": "server", "command": "qod fleet join ... --name <another-name>"},
                        {"where": "manager", "command": "qod fleet remove srv-1"}],
            "retryable": False}
    ok = FakeResponse(200, {"heartbeatSec": 5, "assignment": None})
    member = make_member(FakeHttp([ok, FakeResponse(409, body)]), lambda *a, **k: FakeProc(), tmp_path)
    member.run_once()
    assert member.run_once() == BACKOFF_MIN_S  # after a first contact a refusal never exits
    err = capsys.readouterr().err
    assert "registered at 10.0.0.9:21900" in err
    assert "to fix (any one):" in err and "on this server:" in err and "qod fleet remove srv-1" in err
    assert "hint:" not in err


def test_non_retryable_refusal_before_first_contact_exits(tmp_path, capsys):
    body = {"error": "fleet_unauthorized", "message": "invalid or missing X-Fleet-Token", "retryable": False,
            "actions": [{"where": "server", "command": "export QOD_FLEET_JOIN_TOKEN=<the manager's QOD_FLEET_JOIN_TOKEN>"}]}
    member = make_member(FakeHttp([FakeResponse(401, body)]), lambda *a, **k: FakeProc(), tmp_path)
    with pytest.raises(SystemExit) as e:
        member.run_once()
    assert e.value.code == 2
    assert "export QOD_FLEET_JOIN_TOKEN" in capsys.readouterr().err


def test_no_fleet_endpoint_before_first_contact_exits(tmp_path):
    member = make_member(FakeHttp([FakeResponse(404, None)]), lambda *a, **k: FakeProc(), tmp_path)
    with pytest.raises(SystemExit):
        member.run_once()


def test_retryable_refusal_before_first_contact_keeps_retrying(tmp_path):
    body = {"error": "backend_error", "message": "fleet store error, see manager log", "retryable": True}
    member = make_member(FakeHttp([FakeResponse(502, body)]), lambda *a, **k: FakeProc(), tmp_path)
    assert member.run_once() == BACKOFF_MIN_S


def test_a_repeated_failure_prints_one_line_per_retry(tmp_path, capsys):
    refused = lambda: FakeResponse(409, {"error": "address_change_refused", "message": "known"})
    member = make_member(FakeHttp([refused(), refused(), refused()]), lambda *a, **k: FakeProc(), tmp_path)
    for _ in range(3):
        member.run_once()
    err = capsys.readouterr().err.splitlines()
    assert sum("WARN manager answered 409" in l for l in err) == 1
    assert [l for l in err if "still failing" in l] == [
        "qod fleet join: still failing (address_change_refused, 2 times), retrying",
        "qod fleet join: still failing (address_change_refused, 3 times), retrying",
    ]


def test_network_failure_leads_with_the_explanation(tmp_path, capsys, plain_http_port):
    url = f"https://127.0.0.1:{plain_http_port}"
    ok = FakeResponse(200, {"heartbeatSec": 5, "assignment": None})
    member = make_member(RaisingHttp(_real_error(url), [ok]), lambda *a, **k: FakeProc(), tmp_path)
    member.run_once()
    member.run_once()
    err = capsys.readouterr().err.splitlines()
    line = next(l for l in err if "cannot reach the manager" in l)
    assert "answered plain HTTP" in line and "WRONG_VERSION_NUMBER" not in line
    assert any("(cause:" in l and "WRONG_VERSION_NUMBER" in l for l in err)


@pytest.mark.parametrize("error,expected", [
    ("exited with 1: ERROR: qod-node not found (QOD_NODE_BIN='', 'qod-node' not on PATH)", "qod-node is missing on this server"),
    ("exited with 134: dyld[42]: Library not loaded: @rpath/libduckdb.dylib", "cannot load its libduckdb"),
    ("exited with 127: qod-node: error while loading shared libraries: libduckdb.so: cannot open shared object file", "cannot load its libduckdb"),
    ("exited with 1: IO Error: Could not bind: Address already in use", "another process holds port 21900"),
    ('exited with 1: HTTP Error: Failed to download extension "postgres_scanner" at URL "http://extensions.duckdb.org/x"', "cannot download DuckDB extensions"),
    ('exited with 1: IO Error: Unable to connect to Postgres at "host=pg port=5432": connection to server at "pg" (10.0.0.5), port 5432 failed: Connection refused', "cannot reach the catalog Postgres"),
])
def test_node_failure_action_classifies_known_failures(error, expected):
    assert expected in node_failure_action(error, 21900)


def test_node_failure_action_leaves_unknown_failures_alone():
    assert node_failure_action("exited with 3: something else", 21900) is None
    assert node_failure_action(None, 21900) is None


def test_failed_node_reports_and_prints_its_suggested_action(tmp_path, capsys):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=300 + len(procs), alive=False)
        p.stderr = io.BytesIO(b"IO Error: Could not bind: Address already in use\n")
        procs.append(p); return p
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}) for _ in range(2)])
    member = make_member(http, popen, tmp_path, port_open=lambda p: False)
    member.run_once()   # start, dies at once
    member.run_once()   # reports failed, schedules the restart
    node = http.requests[1][1]["node"]
    assert node["state"] == "failed"
    assert node["action"] == ("another process holds port 21900 on this server: stop it, or re-run "
                              "qod fleet join with --node-port <free-port>")
    assert "to fix: another process holds port 21900" in capsys.readouterr().err
