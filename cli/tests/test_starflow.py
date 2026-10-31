import importlib
import os
import sys
from pathlib import Path

import pytest

from qod_cli import starflow
from qod_cli.commands import _starflow as starflow_cmd
from qod_cli.starflow import PgCoords, StarflowError

posix_only = pytest.mark.skipif(
    sys.platform == "win32", reason="needs POSIX process groups, signals or exec bits"
)

PG = PgCoords(host="localhost", port=25432, user="postgres", password="postgres")


def test_parse_env_file_handles_comments_export_and_quotes(tmp_path):
    f = tmp_path / "x.env"
    f.write_text(
        "# comment\n"
        "\n"
        "SL_API_HTTP_PORT=9000\n"
        "export SL_FS=file://\n"
        'SL_API_STARLAKE_CORE_ENV_VARS="SPARK_DRIVER_MEMORY=1g"\n'
        "QUOTED='a b'\n"
        "EMPTY=\n"
        "not a pair\n"
    )
    assert starflow.parse_env_file(f) == {
        "SL_API_HTTP_PORT": "9000",
        "SL_FS": "file://",
        "SL_API_STARLAKE_CORE_ENV_VARS": "SPARK_DRIVER_MEMORY=1g",
        "QUOTED": "a b",
        "EMPTY": "",
    }


def test_modules_import_without_posix_only_os_attributes(monkeypatch):
    # Windows has no os.killpg / os.getpgid: importing must not touch them, or the
    # whole CLI (main -> start -> _starflow -> starflow) dies at import there.
    saved = {m: dict(m.__dict__) for m in (starflow, starflow_cmd)}
    monkeypatch.delattr(os, "killpg", raising=False)
    monkeypatch.delattr(os, "getpgid", raising=False)
    try:
        importlib.reload(starflow)
        importlib.reload(starflow_cmd)
    finally:
        monkeypatch.undo()
        # Put the original objects back (a second reload would mint new classes,
        # breaking names other tests imported, such as StarflowError).
        for m, d in saved.items():
            m.__dict__.clear()
            m.__dict__.update(d)


def _install_fake(home: Path) -> Path:
    run_api = home / starflow.RUN_API
    run_api.parent.mkdir(parents=True)
    run_api.write_text("#!/bin/sh\n")
    return home


def test_resolve_home_prefers_explicit(tmp_path):
    assert starflow.resolve_home("~/x", tmp_path / "cache", tmp_path) == Path("~/x").expanduser()


def test_resolve_home_uses_installed_user_starlake(tmp_path):
    _install_fake(tmp_path / "starlake")
    assert starflow.resolve_home(None, tmp_path / "cache", tmp_path) == tmp_path / "starlake"


def test_resolve_home_falls_back_to_cache(tmp_path):
    (tmp_path / "starlake").mkdir()  # exists but not installed
    assert starflow.resolve_home(None, tmp_path / "cache", tmp_path) == tmp_path / "cache" / "starflow"


def test_port_and_url_defaults():
    assert starflow.resolve_port_and_url(None, None, {}, {}) == (9900, "http://localhost:9900")


def test_port_and_url_precedence_flag_file_env():
    file_env = {"SL_API_HTTP_PORT": "9000", "SL_URL": "http://file:1/"}
    proc_env = {"SL_API_HTTP_PORT": "9100", "SL_URL": "http://env:2"}
    assert starflow.resolve_port_and_url(None, None, file_env, proc_env) == (9000, "http://file:1")
    assert starflow.resolve_port_and_url(None, None, {}, proc_env) == (9100, "http://env:2")
    assert starflow.resolve_port_and_url(7000, "https://sf.example.com", file_env, proc_env) == (
        7000,
        "https://sf.example.com",
    )


def test_port_and_url_default_url_follows_resolved_port():
    assert starflow.resolve_port_and_url(None, None, {"SL_API_HTTP_PORT": "9000"}, {}) == (
        9000,
        "http://localhost:9000",
    )


@pytest.mark.parametrize("bad", [{"SL_API_HTTP_PORT": "abc"}, {"SL_URL": "sf.example.com"}])
def test_port_and_url_rejects_garbage(bad):
    with pytest.raises(StarflowError):
        starflow.resolve_port_and_url(None, None, bad, {})


def test_shared_secret_generated_once_and_persisted():
    saved = {}
    secret = starflow.ensure_shared_secrets({}, saved.update, token=lambda n: f"t{n}")
    assert secret == "t48"
    assert saved == {"QOD_SESSION_JWT_SECRET": "t48"}


def test_shared_secret_reuses_the_stored_one_and_never_mints_a_key():
    saved = {}
    env = {"QOD_API_KEY": "stale", "QOD_SESSION_JWT_SECRET": "s"}
    assert starflow.ensure_shared_secrets(env, saved.update, token=lambda n: f"t{n}") == "s"
    assert saved == {}


def test_manager_env():
    assert starflow.manager_env("http://sf:9000", "s") == {
        "SL_ENABLED": "true",
        "SL_URL": "http://sf:9000",
        "QOD_SESSION_JWT_SECRET": "s",
        "JWT_SECRET_KEY": "s",
    }


def _sf_env(proc_env=None, file_env=None, mgr_env=None, url="http://localhost:9900"):
    return starflow.starflow_env(
        proc_env or {},
        file_env or {},
        mgr_env or {},
        port=9900,
        url=url,
        secret="s",
        pg=PG,
        java_home="/jdk",
    )


def test_starflow_env_defaults():
    env = _sf_env()
    assert env["QOD_ENABLED"] == "true"
    assert env["QOD_URL"] == "http://localhost:20900"
    assert env["QOD_FLIGHT_URL"] == "https://localhost:31338"
    assert env["QOD_TLS_INSECURE"] == "true"
    assert env["QOD_UI_URL"] == "http://localhost:20900/ui"
    assert "QOD_API_KEY" not in env
    assert env["JWT_SECRET_KEY"] == "s"
    assert env["SL_API_HTTP_PORT"] == "9900"
    assert env["SL_API_HTTP_FRONT_URL"] == "http://localhost:9900"
    assert env["SL_API_DOMAIN"] == "localhost"
    assert env["SL_API_JDBC_URL"] == "jdbc:postgresql://localhost:25432/starlake"
    assert env["SL_API_JDBC_USER"] == "postgres"
    assert env["SL_API_JDBC_PASSWORD"] == "postgres"
    assert env["SL_API_JDBC_DRIVER"] == "org.postgresql.Driver"
    assert env["JAVA_HOME"] == "/jdk"


def test_starflow_env_follows_manager_ports_tls_and_public_url():
    env = _sf_env(
        mgr_env={
            "QOD_ON_DEMAND_PORT": "21000",
            "PROXY_PORT": "32000",
            "PROXY_TLS_ENABLED": "false",
            "QOD_PUBLIC_BASE_URL": "https://qod.example.com/",
        },
        url="https://sf.example.com",
    )
    assert env["QOD_URL"] == "http://localhost:21000"
    assert env["QOD_FLIGHT_URL"] == "http://localhost:32000"
    assert env["QOD_UI_URL"] == "https://qod.example.com/ui"
    assert env["SL_API_HTTP_FRONT_URL"] == "https://sf.example.com"
    assert env["SL_API_DOMAIN"] == "sf.example.com"


def test_starflow_env_precedence_and_java_opts_drop():
    env = _sf_env(
        proc_env={"JAVA_OPTS": "-Xmx8g", "PATH": "/bin", "SL_API_MODE": "LOCAL"},
        file_env={"SL_API_MODE": "ALL", "QOD_API_KEY": "stale", "SL_API_JDBC_URL": "jdbc:h2:x"},
    )
    assert "JAVA_OPTS" not in env
    assert env["PATH"] == "/bin"
    assert env["SL_API_MODE"] == "ALL"  # env file beats process env
    assert "QOD_API_KEY" not in env  # not even the env file's
    assert env["SL_API_JDBC_URL"] == "jdbc:postgresql://localhost:25432/starlake"


def test_starflow_env_orchestrator_url_follows_starflow_url():
    env = _sf_env(url="https://sf.example.com")
    assert env["SL_API_ORCHESTRATOR_URL"] == "https://sf.example.com/airflow/"


@pytest.mark.parametrize("where", ["proc_env", "file_env"])
def test_starflow_env_user_orchestrator_url_wins(where):
    env = _sf_env(**{where: {"SL_API_ORCHESTRATOR_URL": "http://airflow:8080/"}})
    assert env["SL_API_ORCHESTRATOR_URL"] == "http://airflow:8080/"


def test_starflow_env_file_may_set_java_opts():
    env = _sf_env(proc_env={"JAVA_OPTS": "-Xmx8g"}, file_env={"JAVA_OPTS": "-Xmx2g"})
    assert env["JAVA_OPTS"] == "-Xmx2g"


@pytest.mark.parametrize("where", ["proc_env", "file_env"])
def test_starflow_env_never_carries_qod_api_key(where):
    # The pairing holds no static key, and an older Starflow holding one sends it
    # instead of falling back to the user's own QoD session.
    env = _sf_env(**{where: {"QOD_API_KEY": "stale", "SL_API_MODE": "ALL"}})
    assert "QOD_API_KEY" not in env
    assert env["SL_API_MODE"] == "ALL"  # the rest of that source still applies


import os
import subprocess
from types import SimpleNamespace


def test_latest_version_strips_v_prefix():
    calls = []

    def fake_get(url, **kw):
        calls.append(url)
        return SimpleNamespace(raise_for_status=lambda: None, json=lambda: {"tag_name": "v1.8.8"})

    assert starflow.latest_version(get=fake_get) == "1.8.8"
    assert calls == [starflow.RELEASES_LATEST_URL]


def test_java_home_of_reads_java_home_property():
    stderr = "Property settings:\n    java.home = /opt/jdk-21\n    java.version = 21\n"

    def fake_run(cmd, **kw):
        assert cmd == ["/usr/bin/java", "-XshowSettings:properties", "-version"]
        return SimpleNamespace(stderr=stderr, returncode=0)

    assert starflow.java_home_of("/usr/bin/java", run=fake_run) == "/opt/jdk-21"


def test_java_home_of_falls_back_to_binary_parent(tmp_path):
    java = tmp_path / "jdk" / "bin" / "java"
    java.parent.mkdir(parents=True)
    java.write_text("")
    fake_run = lambda cmd, **kw: SimpleNamespace(stderr="", returncode=0)
    # realpath on both sides: macOS tmp dirs live behind the /var -> /private/var symlink.
    expected = str(Path(os.path.realpath(java)).parent.parent)
    assert starflow.java_home_of(str(java), run=fake_run) == expected


@posix_only
def test_install_downloads_script_then_runs_install(tmp_path):
    home = tmp_path / "sf"
    seen = {}

    def fake_download(url, dest):
        seen["url"] = url
        dest.write_text("#!/bin/sh\n")

    def fake_run(cmd, cwd=None, env=None):
        seen["cmd"], seen["cwd"], seen["env"] = cmd, cwd, env
        _install_fake(home)
        return SimpleNamespace(returncode=0)

    starflow.install(home, "1.8.8", "/jdk", download=fake_download, run=fake_run)
    assert seen["url"] == starflow.STARLAKE_SH_URL
    assert seen["cmd"] == [str(home / "starlake"), "install"]
    assert seen["cwd"] == home
    assert seen["env"]["SL_VERSION"] == "1.8.8"
    assert seen["env"]["JAVA_HOME"] == "/jdk"
    assert (home / "starlake").stat().st_mode & 0o100


def test_install_failure_raises(tmp_path):
    fake_download = lambda url, dest: dest.write_text("")
    fake_run = lambda cmd, cwd=None, env=None: SimpleNamespace(returncode=3)
    with pytest.raises(StarflowError, match="exit 3"):
        starflow.install(tmp_path / "sf", "1.8.8", "/jdk", download=fake_download, run=fake_run)


def test_ensure_installed_skips_installed_home(tmp_path):
    home = _install_fake(tmp_path / "sf")
    boom = lambda *a, **kw: pytest.fail("must not install")
    starflow.ensure_installed(home, None, "/jdk", echo=print, latest=boom, installer=boom)


def test_ensure_installed_uses_latest_when_no_version(tmp_path):
    seen, lines = {}, []
    starflow.ensure_installed(
        tmp_path / "sf",
        None,
        "/jdk",
        echo=lines.append,
        latest=lambda: "1.9.0",
        installer=lambda home, version, java_home: seen.update(v=version, j=java_home),
    )
    assert seen == {"v": "1.9.0", "j": "/jdk"}
    assert "installing Starflow 1.9.0" in lines[0]


def test_ensure_installed_honors_pinned_version(tmp_path):
    seen = {}
    starflow.ensure_installed(
        tmp_path / "sf",
        "1.8.0",
        "/jdk",
        echo=lambda line: None,
        latest=lambda: pytest.fail("pinned version must not look up latest"),
        installer=lambda home, version, java_home: seen.update(v=version),
    )
    assert seen == {"v": "1.8.0"}


def _recording_installer(seen):
    return lambda home, version, java_home: seen.append(home)


def test_ensure_installed_refuses_a_non_empty_foreign_dir(tmp_path):
    home = tmp_path / "projects"
    home.mkdir()
    (home / "notes.txt").write_text("mine")
    with pytest.raises(StarflowError) as exc:
        starflow.ensure_installed(
            home, "1.8.0", "/jdk", echo=lambda l: None,
            latest=lambda: pytest.fail("must not look up latest"),
            installer=lambda *a: pytest.fail("must not install"),
        )
    assert str(exc.value) == (
        f"{home} is not a Starflow install and is not empty; install Starflow there "
        "yourself or pick an empty directory (--starflow-home or STARLAKE_HOME)"
    )


def test_ensure_installed_treats_a_finder_ds_store_only_dir_as_empty(tmp_path):
    home = tmp_path / "sf"
    home.mkdir()
    (home / ".DS_Store").write_bytes(b"\x00\x00")
    seen = []
    starflow.ensure_installed(
        home, "1.8.0", "/jdk", echo=lambda l: None,
        latest=lambda: pytest.fail("pinned version must not look up latest"),
        installer=_recording_installer(seen),
    )
    assert seen == [home]


def test_ensure_installed_refuses_ds_store_plus_foreign_files(tmp_path):
    home = tmp_path / "sf"
    home.mkdir()
    (home / ".DS_Store").write_bytes(b"\x00")
    (home / "notes.txt").write_text("mine")
    with pytest.raises(StarflowError):
        starflow.ensure_installed(
            home, "1.8.0", "/jdk", echo=lambda l: None,
            latest=lambda: pytest.fail("must not look up latest"),
            installer=lambda *a: pytest.fail("must not install"),
        )


@pytest.mark.parametrize("layout", ["missing", "empty", "only_starlake_script"])
def test_ensure_installed_proceeds_into_missing_empty_or_resumable_dir(tmp_path, layout):
    home = tmp_path / "sf"
    if layout != "missing":
        home.mkdir()
    if layout == "only_starlake_script":
        # Left by an earlier interrupted install: must stay resumable.
        (home / "starlake").write_text("#!/bin/sh\n")
    seen = []
    starflow.ensure_installed(
        home, "1.8.0", "/jdk", echo=lambda l: None, installer=_recording_installer(seen)
    )
    assert seen == [home]


import signal


class FakeConn:
    def __init__(self, existing):
        self.existing, self.ran, self.closed = existing, [], False

    def run(self, sql, **params):
        self.ran.append((sql, params))
        if sql.startswith("SELECT"):
            return [[1]] if params["n"] in self.existing else []
        return None

    def close(self):
        self.closed = True


def test_ensure_database_creates_when_missing():
    conn, seen = FakeConn(existing=set()), {}

    def connect(**kw):
        seen.update(kw)
        return conn

    assert starflow.ensure_database(PG, connect=connect) is True
    assert seen == {
        "user": "postgres",
        "password": "postgres",
        "host": "localhost",
        "port": 25432,
        "database": "postgres",
    }
    assert conn.ran[-1] == ('CREATE DATABASE "starlake"', {})
    assert conn.closed


def test_ensure_database_noop_when_present():
    conn = FakeConn(existing={"starlake"})
    assert starflow.ensure_database(PG, connect=lambda **kw: conn) is False
    assert len(conn.ran) == 1
    assert conn.closed


@posix_only
def test_launch_writes_pid_and_logs_and_detaches(tmp_path):
    home = _install_fake(tmp_path / "sf")
    (home / "bin/api/bin/local-application").write_text("")
    seen = {}

    def fake_popen(cmd, **kw):
        seen["cmd"], seen["kw"] = cmd, kw
        return SimpleNamespace(pid=4242)

    proc = starflow.launch(home, {"A": "1"}, tmp_path / "state", popen=fake_popen)
    assert proc.pid == 4242
    assert seen["cmd"] == [str(home / starflow.RUN_API), str(home)]
    assert seen["kw"]["start_new_session"] is True
    assert seen["kw"]["stdin"] is subprocess.DEVNULL
    assert seen["kw"]["env"] == {"A": "1"}
    assert (tmp_path / "state" / starflow.PID_FILE).read_text() == f"4242\n{home}\n"
    assert starflow.log_path(tmp_path / "state").exists()
    assert (home / "bin/api/bin/local-application").stat().st_mode & 0o100


def _world(alive_polls):
    """killpg fake: the group answers signal 0 for `alive_polls` polls, then dies."""
    log = {"sent": [], "polls": alive_polls}

    def killpg(pgid, sig):
        if sig == 0:
            if log["polls"] <= 0:
                raise ProcessLookupError
            log["polls"] -= 1
            return
        log["sent"].append((pgid, sig))

    return log, killpg


HOME = "/opt/starflow-home"


def _pid_file(state_dir, content):
    (state_dir / starflow.PID_FILE).write_text(content)


def _ours(pgid):
    # The leader (bash local-run-api) is gone; the JVM it started survives in the group.
    return [f"/usr/bin/java -cp {HOME}/bin/api/lib/* ai.starlake.Main"]


def _no_signal(*a):
    pytest.fail("must not signal")


def test_group_commands_parses_ps_output():
    out = (
        "  4242 /bin/bash /opt/sf/bin/api/bin/local-run-api /opt/sf\n"
        "  77 /usr/sbin/sshd -D\n"
        "garbage line\n"
        "\n"
        "4242 java -cp x   ai.starlake.Main\n"
        "424 other\n"
    )
    seen = {}

    def run(cmd, **kw):
        seen["cmd"], seen["kw"] = cmd, kw
        return SimpleNamespace(returncode=0, stdout=out)

    assert starflow.group_commands(4242, run=run) == [
        "/bin/bash /opt/sf/bin/api/bin/local-run-api /opt/sf",
        "java -cp x   ai.starlake.Main",
    ]
    assert seen["cmd"] == ["ps", "-A", "-o", "pgid=,command="]
    assert seen["kw"]["capture_output"] is True and seen["kw"]["text"] is True


def test_group_commands_failing_ps_is_empty():
    nonzero = lambda cmd, **kw: SimpleNamespace(returncode=1, stdout="4242 java\n")
    assert starflow.group_commands(4242, run=nonzero) == []

    def oserror(cmd, **kw):
        raise OSError("no ps")

    assert starflow.group_commands(4242, run=oserror) == []


@posix_only
def test_stop_running_sigterm_then_clean_exit(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=1)
    asked = []
    stopped = starflow.stop_running(
        tmp_path, echo=lambda l: None, killpg=killpg, sleep=lambda s: None,
        commands=lambda pgid: asked.append(pgid) or _ours(pgid),
    )
    assert stopped is True
    assert asked == [4242]
    assert log["sent"] == [(4242, signal.SIGTERM)]
    assert not (tmp_path / starflow.PID_FILE).exists()


@posix_only
def test_stop_running_escalates_to_sigkill(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=10_000)
    starflow.stop_running(
        tmp_path, echo=lambda l: None, killpg=killpg, sleep=lambda s: None,
        timeout_s=1.0, commands=_ours,
    )
    assert log["sent"] == [(4242, signal.SIGTERM), (4242, signal.SIGKILL)]
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_stop_running_ignores_empty_group(tmp_path):
    # Process gone: no member left in the group.
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    assert starflow.stop_running(
        tmp_path, echo=lambda l: None, killpg=_no_signal, commands=lambda pgid: []
    ) is False
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_stop_running_ignores_reused_pid_not_mentioning_home(tmp_path):
    # Pid reused by an unrelated group: no member mentions the Starflow home.
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    foreign = lambda pgid: ["/usr/sbin/sshd -D", "/bin/zsh -l"]
    assert starflow.stop_running(
        tmp_path, echo=lambda l: None, killpg=_no_signal, commands=foreign
    ) is False
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_stop_running_old_one_line_pid_file_is_stale(tmp_path):
    _pid_file(tmp_path, "4242")
    assert starflow.stop_running(
        tmp_path, echo=lambda l: None, killpg=_no_signal, commands=_ours
    ) is False
    assert not (tmp_path / starflow.PID_FILE).exists()


@pytest.mark.parametrize("content", ["garbage\n/opt/x\n", "", "0\n/opt/x\n", "-5\n/opt/x\n"])
def test_stop_running_garbage_pid_file_is_stale(tmp_path, content):
    _pid_file(tmp_path, content)
    assert starflow.stop_running(
        tmp_path, echo=lambda l: None, killpg=_no_signal, commands=lambda pgid: [f"x {pgid} /opt/x"]
    ) is False
    assert not (tmp_path / starflow.PID_FILE).exists()


@posix_only
def test_stop_running_removes_pid_file_when_wait_interrupted(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=10_000)

    def interrupted(s):
        raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        starflow.stop_running(
            tmp_path, echo=lambda l: None, killpg=killpg, sleep=interrupted, commands=_ours
        )
    assert log["sent"] == [(4242, signal.SIGTERM)]
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_stop_running_without_pid_file(tmp_path):
    assert starflow.stop_running(tmp_path, echo=lambda l: None) is False


@posix_only
def test_begin_stop_sigterms_and_keeps_pid_file_until_finish(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=1)
    pgid = starflow.begin_stop(tmp_path, echo=lambda l: None, killpg=killpg, commands=_ours)
    assert pgid == 4242
    assert log["sent"] == [(4242, signal.SIGTERM)]
    assert (tmp_path / starflow.PID_FILE).exists()
    starflow.finish_stop(tmp_path, pgid, echo=lambda l: None, killpg=killpg, sleep=lambda s: None)
    assert log["sent"] == [(4242, signal.SIGTERM)]
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_begin_stop_stale_file_removed_without_signalling(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    assert starflow.begin_stop(
        tmp_path, echo=lambda l: None, killpg=_no_signal, commands=lambda pgid: []
    ) is None
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_begin_stop_without_pid_file(tmp_path):
    assert starflow.begin_stop(tmp_path, echo=lambda l: None, killpg=_no_signal) is None


@posix_only
def test_finish_stop_escalates_to_sigkill(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=10_000)
    asked = []
    starflow.finish_stop(
        tmp_path, 4242, echo=lambda l: None, killpg=killpg, sleep=lambda s: None, timeout_s=1.0,
        commands=lambda pgid: asked.append(pgid) or _ours(pgid),
    )
    assert asked == [4242]
    assert log["sent"] == [(4242, signal.SIGKILL)]
    assert not (tmp_path / starflow.PID_FILE).exists()


@posix_only
@pytest.mark.parametrize(
    "members", [[], ["/usr/sbin/sshd -D", "/bin/zsh -l"]], ids=["group_gone", "group_reused"]
)
def test_finish_stop_skips_sigkill_when_group_no_longer_ours(tmp_path, members):
    # The group outlived the wait, but by escalation time no member runs from
    # the recorded home any more (pgid reused): never SIGKILL a stranger.
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=10_000)
    starflow.finish_stop(
        tmp_path, 4242, echo=lambda l: None, killpg=killpg, sleep=lambda s: None, timeout_s=1.0,
        commands=lambda pgid: members,
    )
    assert log["sent"] == []
    assert not (tmp_path / starflow.PID_FILE).exists()


@posix_only
def test_finish_stop_skips_sigkill_when_pid_file_lost_its_home(tmp_path):
    _pid_file(tmp_path, "4242\n")
    log, killpg = _world(alive_polls=10_000)
    starflow.finish_stop(
        tmp_path, 4242, echo=lambda l: None, killpg=killpg, sleep=lambda s: None, timeout_s=1.0,
        commands=_ours,
    )
    assert log["sent"] == []
    assert not (tmp_path / starflow.PID_FILE).exists()


@posix_only
def test_finish_stop_removes_pid_file_when_wait_interrupted(tmp_path):
    _pid_file(tmp_path, f"4242\n{HOME}\n")
    log, killpg = _world(alive_polls=10_000)

    def interrupted(s):
        raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        starflow.finish_stop(tmp_path, 4242, echo=lambda l: None, killpg=killpg, sleep=interrupted)
    assert log["sent"] == []
    assert not (tmp_path / starflow.PID_FILE).exists()


def test_run_after_ready_reports_teardown_as_stopped(tmp_path):
    proc = SimpleNamespace(pid=4242, wait=lambda: -15)
    lines = []
    starflow.run_after_ready(
        client="c", pg=PG, home=tmp_path, env={}, state_dir=tmp_path, url="http://sf:9000",
        ready_timeout=5.0, echo=lines.append, wait=lambda client, timeout_s: None,
        ensure_db=lambda pg: None, launch_fn=lambda *a: proc,
    )
    assert lines[-1] == "Starflow stopped."
    assert not any("exited with code" in l for l in lines)


def test_run_after_ready_happy_path(tmp_path):
    order, lines = [], []
    proc = SimpleNamespace(pid=4242, wait=lambda: 0)
    starflow.run_after_ready(
        client="c", pg=PG, home=tmp_path, env={"E": "1"}, state_dir=tmp_path, url="http://sf:9000",
        ready_timeout=5.0, echo=lines.append,
        wait=lambda client, timeout_s: order.append(("wait", client, timeout_s)),
        ensure_db=lambda pg: order.append(("db", pg)),
        launch_fn=lambda home, env, state_dir: order.append(("launch", env)) or proc,
    )
    assert order == [("wait", "c", 5.0), ("db", PG), ("launch", {"E": "1"})]
    assert any("Starflow: http://sf:9000" in l for l in lines)
    assert any("exited with code 0" in l for l in lines)


def test_run_after_ready_reports_failure_without_raising(tmp_path):
    lines = []

    def bad_db(pg):
        raise OSError("connection refused")

    starflow.run_after_ready(
        client="c", pg=PG, home=tmp_path, env={}, state_dir=tmp_path, url="http://sf:9000",
        ready_timeout=5.0, echo=lines.append, wait=lambda client, timeout_s: None,
        ensure_db=bad_db, launch_fn=lambda *a: pytest.fail("must not launch"),
    )
    assert lines == ["Starflow not started: connection refused"]


def _run_after_ready_with(tmp_path, pid, on_wait):
    proc = SimpleNamespace(pid=pid, wait=lambda: on_wait() or 0)
    lines = []
    starflow.run_after_ready(
        client="c", pg=PG, home=tmp_path, env={}, state_dir=tmp_path, url="http://sf:9000",
        ready_timeout=5.0, echo=lines.append, wait=lambda client, timeout_s: None,
        ensure_db=lambda pg: None, launch_fn=lambda *a: proc,
    )
    return lines


def test_run_after_ready_removes_own_pid_file_after_exit(tmp_path):
    lines = _run_after_ready_with(
        tmp_path, 4242, lambda: _pid_file(tmp_path, f"4242\n{HOME}\n")
    )
    assert not (tmp_path / starflow.PID_FILE).exists()
    assert any("exited with code 0" in l for l in lines)


def test_run_after_ready_keeps_pid_file_of_a_newer_run(tmp_path):
    _run_after_ready_with(tmp_path, 4242, lambda: _pid_file(tmp_path, f"5555\n{HOME}\n"))
    assert (tmp_path / starflow.PID_FILE).read_text() == f"5555\n{HOME}\n"
