"""Stopping one manager only touches that manager's nodes.

Every node's wrapper argv and node directory carry its manager's marker `<rest-port>-<boot id>`
(LocalQuackBackend.managerMarker), and the stop paths match on it. These tests start two wrappers
of the real spawn script, with stand-in qod-node nodes, under two markers whose REST ports no real
manager uses, then stop one manager and check the other's node survives.
"""

import os
import pathlib
import subprocess
import sys
import time

import pytest

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="bash scripts")

REPO = pathlib.Path(__file__).resolve().parents[2]
SPAWN = REPO / "scripts" / "spawn-quack-node.sh"
KILL = REPO / "scripts" / "kill-quack-nodes.sh"
# REST ports no real manager on this host listens on; the markers only need to be distinct.
MARK_A = "29991-aaaa1111"
MARK_B = "29992-bbbb2222"


def _fake_qod_node(path: pathlib.Path) -> pathlib.Path:
    # Reads its init SQL from stdin, then holds like a real node until signalled.
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "#!/bin/sh\n"
        "cat > /dev/null\n"
        "trap 'exit 143' TERM INT\n"
        "while :; do sleep 1; done\n"
    )
    path.chmod(0o700)
    return path


def _spawn(tmp_path, name, node_port, marker, node_bin):
    """Start the real spawn script; `marker=None` starts an unmarked node (an older manager's)."""
    from qod_cli.fleet_join import write_secrets_dir
    secrets = write_secrets_dir(tmp_path / f"secrets-{name}", {"QOD_NODE_TOKEN": "tok-scope"})
    tmpdir = tmp_path / "tmpdir"
    tmpdir.mkdir(exist_ok=True)
    env = {"PATH": os.environ["PATH"], "TMPDIR": str(tmpdir), "kind": "memory", "dbName": "m",
           "schemaName": "main", "QOD_NODE_BIN": str(node_bin), "QOD_SECRETS_DIR": str(secrets),
           "QOD_SECRETS_DIR_EPHEMERAL": "1", "QOD_NODE_ID": f"quack-scope-{name}"}
    start = tmp_path / f"start-{name}"
    start.mkdir()
    argv = ["bash", str(SPAWN), str(node_port)] + ([marker] if marker else [])
    return subprocess.Popen(argv, cwd=start, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _wait_for(pred, timeout=20.0):
    deadline = time.monotonic() + timeout
    while not pred() and time.monotonic() < deadline:
        time.sleep(0.1)
    return pred()


def _stop_patterns(seen):
    # Every pattern `qod stop` uses to find what to STOP; the unmarked one only counts for a note.
    return [p for p in seen if not p.endswith("[0-9]+$")]


def _pgrep(pattern):
    out = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True).stdout
    return {int(p) for p in out.split()}


def test_the_node_directory_carries_the_manager_marker(tmp_path):
    node_bin = _fake_qod_node(tmp_path / "bin" / "qod-node")
    a = _spawn(tmp_path, "a", 23461, MARK_A, node_bin)
    try:
        assert _wait_for(lambda: _pgrep(rf"qod-node .*--secret-directory .*quack-node\.{MARK_A}\."))
        assert any(p.name.startswith(f"quack-node.{MARK_A}.") for p in (tmp_path / "tmpdir").iterdir())
    finally:
        a.terminate()
        a.wait(timeout=20)


def test_an_invalid_marker_is_refused(tmp_path):
    node_bin = _fake_qod_node(tmp_path / "bin" / "qod-node")
    p = _spawn(tmp_path, "bad", 23462, "not;a-marker", node_bin)
    assert p.wait(timeout=20) == 1


def test_kill_quack_nodes_stops_only_its_own_managers_nodes(tmp_path):
    node_bin = _fake_qod_node(tmp_path / "bin" / "qod-node")
    a = _spawn(tmp_path, "a", 23463, MARK_A, node_bin)
    b = _spawn(tmp_path, "b", 23464, MARK_B, node_bin)
    try:
        assert _wait_for(lambda: _pgrep(rf"qod-node .*--secret-directory .*quack-node\.{MARK_A}\.")
                         and _pgrep(rf"qod-node .*--secret-directory .*quack-node\.{MARK_B}\."))
        # The scoped pattern sees exactly manager A's wrapper, never B's nor anything else.
        assert _pgrep(r"spawn-quack-node\.sh [0-9]+ 29991-[0-9a-f]+") == {a.pid}
        res = subprocess.run(["bash", str(KILL), "--port", "29991"], capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "FORCE_AFTER": "5"}, timeout=60)
        assert res.returncode == 0, res.stdout + res.stderr
        assert a.wait(timeout=20) is not None
        assert b.poll() is None, "manager B's node was stopped by a stop of manager A"
        assert _pgrep(rf"qod-node .*--secret-directory .*quack-node\.{MARK_B}\.")
        assert not _pgrep(rf"qod-node .*--secret-directory .*quack-node\.{MARK_A}\.")
    finally:
        for p in (a, b):
            if p.poll() is None:
                p.terminate()
                p.wait(timeout=20)


def test_kill_quack_nodes_documents_all_in_its_usage():
    res = subprocess.run(["bash", str(KILL), "--help"], capture_output=True, text=True, timeout=30)
    assert res.returncode == 0
    assert "--all" in res.stdout and "--port" in res.stdout


def test_qod_stop_matches_only_its_managers_nodes(monkeypatch):
    from qod_cli.commands import stop as stop_cmd
    seen = []
    monkeypatch.setattr(stop_cmd, "load_start_env", lambda: {})
    monkeypatch.setattr(stop_cmd, "_listening_pid", lambda port: None)
    monkeypatch.setattr(stop_cmd, "_pgrep", lambda pattern: seen.append(pattern) or [])
    monkeypatch.setenv("QOD_ON_DEMAND_PORT", "29993")
    stop_cmd._stop_manager_and_nodes()
    stops = _stop_patterns(seen)
    assert stops and all("29993-" in p for p in stops), seen


def test_qod_stop_uses_the_port_qod_setup_saved(monkeypatch):
    # `qod setup` persists the manager's ports; with none in the environment, `qod stop` must use
    # them (as `qod start` does), not stop and scope its node sweep to the default 20900.
    from qod_cli.commands import stop as stop_cmd
    seen, listened = [], []
    monkeypatch.delenv("QOD_ON_DEMAND_PORT", raising=False)
    monkeypatch.delenv("PROXY_PORT", raising=False)
    monkeypatch.setattr(stop_cmd, "load_start_env",
                        lambda: {"QOD_ON_DEMAND_PORT": "29995", "PROXY_PORT": "31495"})
    monkeypatch.setattr(stop_cmd, "_listening_pid", lambda port: listened.append(port) or None)
    monkeypatch.setattr(stop_cmd, "_pgrep", lambda pattern: seen.append(pattern) or [])
    stop_cmd._stop_manager_and_nodes()
    assert set(listened) == {29995, 31495}
    stops = _stop_patterns(seen)
    assert stops and all("29995-" in p for p in stops), seen
    assert not any("20900" in p for p in stops)


def test_qod_stop_prefers_the_environment_over_the_saved_port(monkeypatch):
    from qod_cli.commands import stop as stop_cmd
    seen = []
    monkeypatch.setenv("QOD_ON_DEMAND_PORT", "29996")
    monkeypatch.setattr(stop_cmd, "load_start_env", lambda: {"QOD_ON_DEMAND_PORT": "29995"})
    monkeypatch.setattr(stop_cmd, "_listening_pid", lambda port: None)
    monkeypatch.setattr(stop_cmd, "_pgrep", lambda pattern: seen.append(pattern) or [])
    stop_cmd._stop_manager_and_nodes()
    stops = _stop_patterns(seen)
    assert stops and all("29996-" in p for p in stops), seen


def test_kill_quack_nodes_unmarked_stops_only_unmarked_nodes(tmp_path):
    node_bin = _fake_qod_node(tmp_path / "bin" / "qod-node")
    marked = _spawn(tmp_path, "marked", 23471, MARK_A, node_bin)
    unmarked = _spawn(tmp_path, "unmarked", 23472, None, node_bin)
    try:
        assert _wait_for(lambda: _pgrep(rf"qod-node .*--secret-directory .*quack-node\.{MARK_A}\.")
                         and _pgrep(r"qod-node .*--secret-directory .*quack-node\.[A-Za-z0-9]{6}/secrets"))
        # --unmarked matches every unmarked node on the host: run it only when the only one is ours.
        others = _pgrep(r"spawn-quack-node\.sh [0-9]+$") - {unmarked.pid}
        if others:
            pytest.skip(f"other unmarked spawn-quack-node processes on this host: {sorted(others)}")
        res = subprocess.run(["bash", str(KILL), "--unmarked"], capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "FORCE_AFTER": "5"}, timeout=60)
        assert res.returncode == 0, res.stdout + res.stderr
        assert unmarked.wait(timeout=20) is not None
        assert marked.poll() is None, "a marked node was stopped by --unmarked"
        assert not _pgrep(r"qod-node .*--secret-directory .*quack-node\.[A-Za-z0-9]{6}/secrets")
    finally:
        for p in (marked, unmarked):
            if p.poll() is None:
                p.terminate()
                p.wait(timeout=20)


def test_kill_quack_nodes_refuses_conflicting_modes():
    # Exits on argument parsing, before any process lookup.
    res = subprocess.run(["bash", str(KILL), "--port", "1", "--unmarked"], capture_output=True,
                         text=True, timeout=30)
    assert res.returncode == 2 and "mutually exclusive" in res.stderr


def test_qod_stop_reports_unmarked_nodes_and_leaves_them(monkeypatch, capsys):
    from qod_cli.commands import stop as stop_cmd
    killed = []
    monkeypatch.setattr(stop_cmd, "load_start_env", lambda: {})
    monkeypatch.setenv("QOD_ON_DEMAND_PORT", "29997")
    monkeypatch.setattr(stop_cmd, "_listening_pid", lambda port: None)
    monkeypatch.setattr(stop_cmd, "_pgrep",
                        lambda pattern: ["4242"] if pattern.endswith("[0-9]+$") else [])
    monkeypatch.setattr(stop_cmd, "_kill_all", lambda pids, sig: killed.append(set(pids)))
    stop_cmd._stop_manager_and_nodes()
    assert "leaving 1 unmarked" in capsys.readouterr().out
    assert all(4242 not in k for k in killed)
