import signal
import sys

import pytest

# stop.py's stop() explicitly raises typer.Exit(1) at the top of the function
# on win32 ("qod stop is not supported on Windows yet; use scripts/stop-jar.ps1
# (or taskkill /T on the java process)") before any of the lsof/pgrep/os.kill
# seams this module exercises ever run. The feature is unimplemented on
# Windows, not merely untested, so the whole suite is skipped there.
pytestmark = pytest.mark.skipif(
    sys.platform == "win32",
    reason="qod stop is not implemented on Windows yet (stop.py raises typer.Exit(1) "
    "unconditionally on win32); use scripts/stop-jar.ps1 or taskkill /T",
)


@pytest.fixture
def stop_world(monkeypatch, tmp_path):
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
    # No embedded pgdata dir in these tests: without this, the finally-sweep in
    # perform_stop would resolve to this developer machine's REAL qod data dir
    # and read whatever postmaster.pid happens to live there.
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: tmp_path / "pg" / "pgdata")
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


# --- orphaned embedded postgres sweep (#100) ---------------------------------


def _write_pidfile(pgdata, content):
    pgdata.mkdir(parents=True)
    (pgdata / "postmaster.pid").write_text(content)


def test_sweep_orphaned_postgres_kills_a_live_pid(tmp_path, monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\nother\nlines\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    killed = []
    lines = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: True, terminate=lambda pid: killed.append(pid), echo=lines.append
    )
    assert killed == [4242]
    assert any("stopping orphaned embedded postgres (pid 4242)" in line for line in lines)


def test_sweep_orphaned_postgres_skips_a_dead_pid(tmp_path, monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    killed = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: False, terminate=lambda pid: killed.append(pid)
    )
    assert killed == []


def test_sweep_orphaned_postgres_skips_when_no_pidfile(tmp_path, monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"  # never created
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    killed = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: True, terminate=lambda pid: killed.append(pid)
    )
    assert killed == []


def test_sweep_orphaned_postgres_skips_an_unparseable_pidfile(tmp_path, monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "not-a-pid\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    killed = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: True, terminate=lambda pid: killed.append(pid)
    )
    assert killed == []


def test_stop_command_sweeps_an_orphaned_embedded_postgres_end_to_end(runner, stop_world, monkeypatch, tmp_path):
    """The `qod stop` invocation itself runs the sweep after the normal
    manager/node teardown completes, even when nothing was listening."""
    from qod_cli.main import app
    from qod_cli.commands import stop as stop_cmd

    stop_world["listening"] = {}
    stop_world["spawn"] = []
    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)
    monkeypatch.setattr(stop_cmd, "_pid_alive", lambda pid: True)
    terminated = []
    monkeypatch.setattr(stop_cmd, "_terminate_pid", lambda pid: terminated.append(pid))

    result = runner.invoke(app, ["stop"])
    assert result.exit_code == 0
    assert terminated == [4242]
    assert "stopping orphaned embedded postgres (pid 4242)" in result.output
