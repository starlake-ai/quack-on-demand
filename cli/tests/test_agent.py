import os
import pathlib

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
    def __init__(self, pid=4242, alive=True):
        self.pid, self._alive, self.terminated, self.killed = pid, alive, False, False
        self.stderr = None
        self.stderr_lines = [b"boot\n"]
    def poll(self):
        return None if self._alive else 1
    def terminate(self):
        self.terminated = True; self._alive = False
    def kill(self):
        self.killed = True; self._alive = False
    def wait(self, timeout=None):
        return 0


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
