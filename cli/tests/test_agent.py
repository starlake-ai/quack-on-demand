import io
import signal
import subprocess

import httpx
import pytest

from qod_cli.agent import Agent


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
    monkeypatch.setattr("qod_cli.agent.os.kill", lambda pid, sig: sent.append(("kill", pid, sig)))
    monkeypatch.setattr("qod_cli.agent.os.killpg", lambda pgid, sig: sent.append(("killpg", pgid, sig)))
    return sent


def assignment(epoch, node_id="quack-acme-db-bi-1", port=21900):
    return {"epoch": epoch, "nodeId": node_id, "poolKey": {"tenant": "acme", "tenantDb": "db", "pool": "bi"},
            "port": port, "token": "tok", "kind": "memory", "env": {"pgHost": "h", "pgPassword": "pw"},
            "dbInitSql": "", "objectStoreSql": "", "extraSetupSql": "", "lockdownSql": ""}


def make_agent(http, popen, tmp_path, port_open=lambda p: True, **kw):
    spawn = tmp_path / "spawn.sh"; spawn.write_text("#!/bin/sh\nexit 0\n")
    kw.setdefault("capacity", lambda: (8, 64 << 30))
    return Agent("https://mgr:20900", "secret", name="srv-1", advertise_host="10.0.0.7", bind_host="10.0.0.7",
                 node_port=21900, spawn_script=spawn, duckdb_bin=None, state_dir=tmp_path / "state",
                 insecure=False, http=http, popen=popen, sleep=lambda s: None, port_open=port_open, **kw)


def test_heartbeat_carries_identity_capacity_and_no_node_initially(tmp_path):
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": None})])
    agent = make_agent(http, lambda *a, **k: FakeProc(), tmp_path)
    assert agent.run_once() == 5
    url, body, headers = http.requests[0]
    assert url == "https://mgr:20900/api/fleet/heartbeat"
    assert headers["X-Fleet-Token"] == "secret"
    assert body["name"] == "srv-1" and body["advertiseHost"] == "10.0.0.7" and body["nodePort"] == 21900
    assert body["cpus"] == 8 and body["memoryBytes"] == 64 << 30
    assert body["node"] == {"assignmentEpoch": 0, "nodeId": None, "state": "none", "pid": None, "error": None, "startedAt": None}


def test_assignment_starts_node_with_spawn_contract_and_reports_running(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        procs.append((cmd, env, kw)); return FakeProc()
    http = FakeHttp([
        FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(3)}),
        FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(3)}),
    ])
    agent = make_agent(http, popen, tmp_path)
    agent.run_once()
    cmd, env, kw = procs[0]
    assert cmd[-2:] == ["21900", "tok"] and env["kind"] == "memory" and env["pgPassword"] == "pw"
    assert env["QOD_NODE_BIND"] == "10.0.0.7"
    assert kw["start_new_session"] is True
    assert (tmp_path / "state" / "node.pid").read_text().strip() == "4242"
    agent.run_once()
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
    agent = make_agent(http, popen, tmp_path)
    agent.run_once(); agent.run_once()
    assert procs[0].terminated and len(procs) == 2
    agent.run_once()
    assert procs[1].terminated
    assert not (tmp_path / "state" / "node.pid").exists()
    agent.run_once()
    assert http.requests[3][1]["node"]["state"] == "stopped"


def test_crash_is_restarted_with_backoff_and_reported_failed(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=200 + len(procs), alive=False); procs.append(p); return p
    replies = [FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}) for _ in range(4)]
    http = FakeHttp(replies)
    agent = make_agent(http, popen, tmp_path, port_open=lambda p: False)
    agent.run_once()            # start #1, dies at once
    agent.run_once()            # reports failed, schedules restart with backoff
    assert http.requests[1][1]["node"]["state"] == "failed"
    assert "boot" in http.requests[1][1]["node"]["error"]
    n_before = len(procs)
    agent.run_once()            # inside backoff: no new spawn
    assert len(procs) == n_before
    agent.clock = lambda: agent.next_restart_at + 1  # fast-forward
    agent.run_once()
    assert len(procs) == n_before + 1


def test_reap_orphan_kills_recorded_pid_only_when_it_is_a_spawn_script(tmp_path, monkeypatch):
    state = tmp_path / "state"; state.mkdir()
    (state / "node.pid").write_text("31337")
    killed = []
    monkeypatch.setattr("qod_cli.agent._cmdline", lambda pid: "bash /x/spawn-quack-node.sh 21900 tok" if pid == 31337 else "")
    monkeypatch.setattr("qod_cli.agent.os.kill", lambda pid, sig: killed.append((pid, sig)))
    agent = make_agent(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    assert agent.reap_orphan() == 31337
    assert killed and killed[0][0] == 31337
    assert not (state / "node.pid").exists()
    # a recycled pid that is not our script is left alone
    (state / "node.pid").write_text("31337")
    monkeypatch.setattr("qod_cli.agent._cmdline", lambda pid: "postgres: checkpointer")
    killed.clear()
    assert agent.reap_orphan() is None and killed == []
    assert not (state / "node.pid").exists()


def test_refuses_plain_http_without_insecure(tmp_path):
    with pytest.raises(SystemExit):
        Agent("http://mgr:20900", "s", name="a", advertise_host="h", bind_host="h", node_port=1,
              spawn_script=tmp_path / "x", duckdb_bin=None, state_dir=tmp_path, insecure=False)
    Agent("http://mgr:20900", "s", name="a", advertise_host="h", bind_host="h", node_port=1,
          spawn_script=tmp_path / "x", duckdb_bin=None, state_dir=tmp_path, insecure=True)


def test_node_env_is_an_allowlist_and_sql_keys_come_only_from_the_assignment(tmp_path, monkeypatch):
    monkeypatch.setenv("QOD_FLEET_JOIN_TOKEN", "fleet-wide-secret")
    monkeypatch.setenv("lockdownSql", "X")
    monkeypatch.setenv("SOME_RANDOM_SECRET", "nope")
    monkeypatch.setenv("QOD_S3_ENDPOINT", "minio:9000")
    envs = []
    def popen(cmd, env=None, **kw):
        envs.append(env); return FakeProc()
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    make_agent(http, popen, tmp_path).run_once()
    env = envs[0]
    assert "QOD_FLEET_JOIN_TOKEN" not in env and "SOME_RANDOM_SECRET" not in env
    assert "PATH" in env and env["QOD_S3_ENDPOINT"] == "minio:9000"
    assert env["lockdownSql"] == "" and env["dbInitSql"] == ""
    assert env["pgPassword"] == "pw" and env["kind"] == "memory" and env["QOD_NODE_BIND"] == "10.0.0.7"


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
    make_agent(http, popen, tmp_path).run_once()
    assert {k: envs[0].get(k) for k in passed} == passed


def test_non_2xx_reply_logs_the_manager_error_code_at_warn(tmp_path, capsys):
    refused = FakeResponse(409, {"error": "address_change_refused", "message": "known with another address"})
    refused.text = '{"error":"address_change_refused","message":"known with another address"}'
    unauthorized = FakeResponse(401, {"error": "fleet_unauthorized", "message": "invalid token"})
    not_json = FakeResponse(502, None)
    not_json.json = lambda: (_ for _ in ()).throw(ValueError("not json"))
    not_json.text = "<html>bad gateway</html>"
    agent = make_agent(FakeHttp([refused, unauthorized, not_json]), lambda *a, **k: FakeProc(), tmp_path)
    assert agent.run_once() == 5 and agent.run_once() == 5 and agent.run_once() == 5
    err = capsys.readouterr().err.splitlines()
    assert "WARN" in err[0] and "409" in err[0] and "address_change_refused" in err[0]
    assert "WARN" in err[1] and "fleet_unauthorized" in err[1]
    assert "WARN" in err[2] and "502" in err[2] and "bad gateway" in err[2]


def test_non_json_reply_is_a_failed_heartbeat_and_keeps_the_node(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(); procs.append(p); return p
    bad = FakeResponse(200, None)
    bad.json = lambda: (_ for _ in ()).throw(ValueError("not json"))
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}), bad])
    agent = make_agent(http, popen, tmp_path)
    agent.run_once()
    assert agent.run_once() == 5
    assert not procs[0].terminated and agent.node is not None


def test_malformed_assignment_is_a_failed_heartbeat_and_keeps_the_node(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(); procs.append(p); return p
    broken = assignment(2); del broken["env"]
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
                     FakeResponse(200, {"heartbeatSec": 5, "assignment": broken})])
    agent = make_agent(http, popen, tmp_path)
    agent.run_once()
    assert agent.run_once() == 5
    assert len(procs) == 1 and not procs[0].terminated
    assert agent.node.assignment["epoch"] == 1


def test_network_error_keeps_the_node_running(tmp_path):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(); procs.append(p); return p
    class Flaky(FakeHttp):
        def post(self, url, json=None, headers=None, timeout=None):
            if self.replies:
                return super().post(url, json, headers, timeout)
            raise httpx.ConnectError("manager down")
    agent = make_agent(Flaky([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})]), popen, tmp_path)
    agent.run_once()
    assert agent.run_once() == 5
    assert not procs[0].terminated and agent.node is not None


def test_popen_failure_is_a_failed_heartbeat(tmp_path):
    def popen(cmd, env=None, **kw):
        raise OSError("no bash")
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)})])
    agent = make_agent(http, popen, tmp_path)
    assert agent.run_once() == 5 and agent.node is None


def test_stop_timeout_kills_the_process_group(tmp_path, recorded_signals):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=777, stuck=True); procs.append(p); return p
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
                     FakeResponse(200, {"heartbeatSec": 5, "assignment": None})])
    agent = make_agent(http, popen, tmp_path)
    agent.run_once(); agent.run_once()
    assert procs[0].terminated
    assert ("killpg", 777, signal.SIGKILL) in recorded_signals


def test_stop_kills_the_group_even_when_the_script_already_exited(tmp_path, recorded_signals):
    procs = []
    def popen(cmd, env=None, **kw):
        p = FakeProc(pid=888); procs.append(p); return p
    http = FakeHttp([FakeResponse(200, {"heartbeatSec": 5, "assignment": assignment(1)}),
                     FakeResponse(200, {"heartbeatSec": 5, "assignment": None})])
    agent = make_agent(http, popen, tmp_path)
    agent.run_once()
    procs[0]._alive = False          # script exited on its own; a duckdb child may survive it
    agent.run_once()
    assert not procs[0].terminated
    assert ("killpg", 888, signal.SIGKILL) in recorded_signals


def test_reap_orphan_waits_the_stop_grace_before_the_group_kill(tmp_path, monkeypatch, recorded_signals):
    state = tmp_path / "state"; state.mkdir()
    (state / "node.pid").write_text("31337")
    monkeypatch.setattr("qod_cli.agent._cmdline", lambda pid: "bash spawn-quack-node.sh")
    slept = []
    agent = make_agent(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    agent.sleep = slept.append
    assert agent.reap_orphan() == 31337
    assert recorded_signals[0] == ("kill", 31337, signal.SIGTERM)
    assert recorded_signals[1] == ("kill", -31337, signal.SIGKILL)
    assert sum(slept) >= 60


def test_reap_orphan_never_signals_pid_1(tmp_path, monkeypatch, recorded_signals):
    state = tmp_path / "state"; state.mkdir()
    (state / "node.pid").write_text("1")
    monkeypatch.setattr("qod_cli.agent._cmdline", lambda pid: "bash spawn-quack-node.sh")
    agent = make_agent(FakeHttp([]), lambda *a, **k: FakeProc(), tmp_path)
    assert agent.reap_orphan() is None and recorded_signals == []


def test_scheme_check_is_case_insensitive(tmp_path):
    with pytest.raises(SystemExit):
        Agent("HTTP://mgr:20900", "s", name="a", advertise_host="h", bind_host="h", node_port=1,
              spawn_script=tmp_path / "x", duckdb_bin=None, state_dir=tmp_path, insecure=False)


def test_default_advertise_host_refuses_loopback(monkeypatch):
    import qod_cli.agent as agent_mod
    class Sock:
        def connect(self, addr): raise OSError("no route")
        def getsockname(self): return ("0.0.0.0", 0)
        def close(self): pass
    monkeypatch.setattr(agent_mod.socket, "socket", lambda *a: Sock())
    monkeypatch.setattr(agent_mod.socket, "gethostbyname", lambda h: "127.0.1.1")
    with pytest.raises(SystemExit) as e:
        agent_mod.default_advertise_host()
    assert e.value.code == 2
    def gai(h): raise agent_mod.socket.gaierror("unknown host")
    monkeypatch.setattr(agent_mod.socket, "gethostbyname", gai)
    with pytest.raises(SystemExit):
        agent_mod.default_advertise_host()
