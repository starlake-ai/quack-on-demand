import signal

import pytest


@pytest.fixture
def stop_world(monkeypatch):
    """Fake process world: discovery + kill + sleep seams, returns the log."""
    from qod_cli.commands import stop as stop_cmd

    world = {"listening": {20900: "111", 31338: "111"}, "spawn": ["222"], "duckdb": [],
             "killed": [], "slept": 0}

    monkeypatch.setattr(
        stop_cmd, "_listening_pid", lambda port: world["listening"].get(port)
    )
    monkeypatch.setattr(stop_cmd, "_pgrep", lambda pattern: (
        world["spawn"] if "spawn" in pattern else world["duckdb"]
    ))

    def fake_kill(pid, sig):
        world["killed"].append((pid, sig))

    monkeypatch.setattr(stop_cmd.os, "kill", fake_kill)
    monkeypatch.setattr(stop_cmd.time, "sleep", lambda s: world.__setitem__("slept", world["slept"] + s))
    return world


def test_stop_nothing_running(runner, stop_world):
    from qod_cli.main import app

    stop_world["listening"] = {}
    stop_world["spawn"] = []
    result = runner.invoke(app, ["stop"])
    assert result.exit_code == 0
    assert "nothing running" in result.output


def test_stop_graceful_sigterm(runner, stop_world):
    from qod_cli.main import app

    # First poll after SIGTERM: everything already gone.
    orig = stop_world["listening"]

    def clear_after_term(pid, sig):
        stop_world["killed"].append((pid, sig))
        stop_world["listening"] = {}
        stop_world["spawn"] = []

    from qod_cli.commands import stop as stop_cmd

    stop_cmd.os.kill = clear_after_term  # replaces the fixture's fake
    result = runner.invoke(app, ["stop"])
    assert result.exit_code == 0, result.output
    assert "stopped cleanly" in result.output
    assert (211, signal.SIGTERM) not in stop_world["killed"]  # only known pids
    pids = {pid for pid, sig in stop_world["killed"] if sig == signal.SIGTERM}
    assert pids == {111, 222}
    assert orig  # discovery ran against the default ports


def test_stop_escalates_to_sigkill(runner, stop_world, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setenv("FORCE_AFTER", "3")
    stop_world["duckdb"] = ["333"]
    result = runner.invoke(app, ["stop"])
    # Never clears: SIGKILL sent to everything, still listening -> exit 1.
    assert result.exit_code == 1
    sigkills = {pid for pid, sig in stop_world["killed"] if sig == signal.SIGKILL}
    assert {111, 222, 333} <= sigkills
    assert "SIGKILL" in result.output


def test_stop_honors_port_env_overrides(runner, stop_world, monkeypatch):
    from qod_cli.main import app

    seen = []

    from qod_cli.commands import stop as stop_cmd

    orig_lookup = stop_cmd._listening_pid
    monkeypatch.setattr(
        stop_cmd, "_listening_pid", lambda port: seen.append(port) or None
    )
    stop_world["spawn"] = []
    monkeypatch.setenv("QOD_ON_DEMAND_PORT", "9999")
    monkeypatch.setenv("PROXY_PORT", "8888")
    result = runner.invoke(app, ["stop"])
    assert result.exit_code == 0
    assert 9999 in seen and 8888 in seen
