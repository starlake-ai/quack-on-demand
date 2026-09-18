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
        is_alive=lambda pid: True,
        process_name=lambda pid: "postgres",
        terminate=lambda pid: killed.append(pid),
        echo=lines.append,
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
    monkeypatch.setattr(stop_cmd, "_process_name", lambda pid: "postgres")
    terminated = []
    monkeypatch.setattr(stop_cmd, "_terminate_pid", lambda pid: terminated.append(pid))

    result = runner.invoke(app, ["stop"])
    assert result.exit_code == 0
    assert terminated == [4242]
    assert "stopping orphaned embedded postgres (pid 4242)" in result.output


# --- I1: pid 0 / negative pids never reach a kill --------------------------


@pytest.mark.parametrize("content", ["0\n", "-1\n", "-12345\n"])
def test_read_postmaster_pid_refuses_zero_and_negative_pids(tmp_path, content):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, content)
    assert stop_cmd._read_postmaster_pid(pgdata) is None


def test_read_postmaster_pid_still_accepts_a_real_pid(tmp_path):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\n")
    assert stop_cmd._read_postmaster_pid(pgdata) == 4242


@pytest.mark.parametrize("content", ["0\n", "-1\n", "-12345\n"])
def test_sweep_orphaned_postgres_refuses_pid_0_or_negative(tmp_path, monkeypatch, content):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, content)
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    alive_probed = []
    killed = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: alive_probed.append(pid) or True,
        terminate=lambda pid: killed.append(pid),
    )
    # A pid this dangerous is refused before it is even probed for liveness.
    assert alive_probed == []
    assert killed == []


# --- I2: the pid must still be a postmaster before it is killed ------------


def test_sweep_orphaned_postgres_skips_when_the_pid_was_recycled(tmp_path, monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    killed = []
    lines = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: True,
        process_name=lambda pid: "sshd",
        terminate=lambda pid: killed.append(pid),
        echo=lines.append,
    )
    assert killed == []
    assert any(
        "pid 4242" in line and "sshd" in line and "not touching it" in line for line in lines
    )


def test_sweep_orphaned_postgres_kills_when_the_pid_is_still_a_postmaster(tmp_path, monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    killed = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: True,
        process_name=lambda pid: "postgres",
        terminate=lambda pid: killed.append(pid),
    )
    assert killed == [4242]


def test_sweep_orphaned_postgres_skips_when_identity_lookup_fails(tmp_path, monkeypatch):
    """A raising (or otherwise failed) identity lookup must never be treated
    as a match - fail-safe: never kill a pid that could not be verified."""
    from qod_cli.commands import stop as stop_cmd

    pgdata = tmp_path / "pg" / "pgdata"
    _write_pidfile(pgdata, "4242\n")
    monkeypatch.setattr(stop_cmd, "_embedded_pgdata_dir", lambda: pgdata)

    def boom(pid):
        raise OSError("ps not found")

    killed = []
    stop_cmd.sweep_orphaned_embedded_postgres(
        is_alive=lambda pid: True, process_name=boom, terminate=lambda pid: killed.append(pid)
    )
    assert killed == []


def test_process_name_posix_reads_ps_comm(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "darwin")

    class FakeProc:
        returncode = 0
        stdout = "postgres\n"

    monkeypatch.setattr(stop_cmd.subprocess, "run", lambda *a, **kw: FakeProc())
    assert stop_cmd._process_name(4242) == "postgres"


def test_process_name_posix_returns_none_when_ps_finds_nothing(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "darwin")

    class FakeProc:
        returncode = 1
        stdout = ""

    monkeypatch.setattr(stop_cmd.subprocess, "run", lambda *a, **kw: FakeProc())
    assert stop_cmd._process_name(4242) is None


def test_process_name_posix_returns_none_when_the_probe_raises(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "darwin")

    def boom(*a, **kw):
        raise FileNotFoundError("ps")

    monkeypatch.setattr(stop_cmd.subprocess, "run", boom)
    assert stop_cmd._process_name(4242) is None


def test_process_name_win32_reads_the_tasklist_csv_image_name(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "win32")

    class FakeProc:
        returncode = 0
        stdout = (
            '"Image Name","PID","Session Name","Session#","Mem Usage"\r\n'
            '"postgres.exe","4242","Console","1","12,345 K"\r\n'
        )

    monkeypatch.setattr(stop_cmd.subprocess, "run", lambda *a, **kw: FakeProc())
    assert stop_cmd._process_name(4242) == "postgres.exe"


def test_process_name_win32_returns_none_when_tasklist_finds_no_match(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "win32")

    class FakeProc:
        returncode = 0
        stdout = "INFO: No tasks are running which match the specified criteria.\r\n"

    monkeypatch.setattr(stop_cmd.subprocess, "run", lambda *a, **kw: FakeProc())
    assert stop_cmd._process_name(4242) is None


def test_is_postmaster_name_posix_accepts_a_basename_match(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "darwin")
    assert stop_cmd._is_postmaster_name("postgres") is True
    assert stop_cmd._is_postmaster_name("/usr/lib/postgresql/16/bin/postgres") is True
    assert stop_cmd._is_postmaster_name("sshd") is False
    assert stop_cmd._is_postmaster_name(None) is False


def test_is_postmaster_name_win32_requires_the_exe_image_name(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    monkeypatch.setattr(stop_cmd.sys, "platform", "win32")
    assert stop_cmd._is_postmaster_name("postgres.exe") is True
    assert stop_cmd._is_postmaster_name("notepad.exe") is False
    assert stop_cmd._is_postmaster_name(None) is False
