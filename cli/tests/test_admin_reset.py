import subprocess

import pytest

from qod_cli import launcher


@pytest.fixture
def jar_run(monkeypatch, tmp_path):
    from qod_cli.commands import admin as admin_cmd

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    calls = {}
    monkeypatch.setattr(admin_cmd, "resolve_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "default_data_dir", lambda: tmp_path / "state")

    def fake_run(cmd, input=None, text=None, env=None):
        calls.update(cmd=cmd, input=input, env=env)
        return subprocess.CompletedProcess(cmd, calls.get("rc", 0))

    monkeypatch.setattr(admin_cmd.subprocess, "run", fake_run)
    calls["jar"] = jar
    return calls


def _invoke(runner, calls, *args, input=None):
    from qod_cli.main import app

    return runner.invoke(
        app, ["admin", "reset-password", *args, "--jar", str(calls["jar"])], input=input
    )


def test_reset_pipes_the_password_on_stdin_never_argv(runner, jar_run):
    result = _invoke(runner, jar_run, "--username", "root", input="n3w\n")
    assert result.exit_code == 0, result.output
    assert jar_run["cmd"][-3:] == ["reset-password", "root", "--reenable"]
    assert "n3w" not in " ".join(jar_run["cmd"])
    assert jar_run["input"] == "n3w\n"


def test_reset_defaults_to_every_configured_admin_in_order(runner, jar_run, monkeypatch):
    monkeypatch.setenv("QOD_ADMIN_USERNAME", "ops, root")
    _invoke(runner, jar_run, input="pw\n")
    assert jar_run["cmd"][-3:] == ["reset-password", "ops", "root"]


def test_reset_defaults_to_both_builtin_admin_names(runner, jar_run, monkeypatch):
    monkeypatch.delenv("QOD_ADMIN_USERNAME", raising=False)
    _invoke(runner, jar_run, input="pw\n")
    assert jar_run["cmd"][-3:] == ["reset-password", "admin@localhost.local", "admin"]


def test_reset_username_option_resets_only_that_one(runner, jar_run, monkeypatch):
    monkeypatch.setenv("QOD_ADMIN_USERNAME", "ops,root")
    _invoke(runner, jar_run, "--username", "root", input="pw\n")
    assert jar_run["cmd"][-3:] == ["reset-password", "root", "--reenable"]


def test_reset_reenables_only_a_name_given_with_username(runner, jar_run, monkeypatch):
    # Re-enabling a superuser revives its personal access tokens: the configured admin
    # names reset passwords only, a deliberately disabled one stays disabled.
    monkeypatch.setenv("QOD_ADMIN_USERNAME", "ops,root")
    _invoke(runner, jar_run, "--must-change", input="pw\n")
    assert "--reenable" not in jar_run["cmd"]
    _invoke(runner, jar_run, "--username", "ops", "--must-change", input="pw\n")
    assert jar_run["cmd"][-4:] == ["reset-password", "ops", "--reenable", "--must-change"]


def test_reset_forwards_must_change(runner, jar_run):
    _invoke(runner, jar_run, "--must-change", input="pw\n")
    assert jar_run["cmd"][-1] == "--must-change"


def _embedded_pg(tmp_path, monkeypatch, running: bool):
    """An embedded data dir with a postmaster.pid (port 25432 on line 4); `running`
    decides what the TCP probe on that port answers."""
    from qod_cli.commands import admin as admin_cmd

    pgdata = tmp_path / "state" / "pg" / "pgdata"
    pgdata.mkdir(parents=True)
    (pgdata / "postmaster.pid").write_text("4242\n/data\n1700000000\n25432\n/tmp\n")
    probed = []
    monkeypatch.setattr(
        admin_cmd, "_tcp_open", lambda host, port: probed.append((host, port)) or running
    )
    return probed


def test_reset_picks_embedded_when_its_postgres_is_running(
    runner, jar_run, tmp_path, monkeypatch
):
    probed = _embedded_pg(tmp_path, monkeypatch, running=True)
    result = _invoke(runner, jar_run, input="pw\n")
    assert probed == [("localhost", 25432)]
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "true"
    assert jar_run["env"]["QOD_PG_EMBEDDED_DATA_DIR"] == str(tmp_path / "state" / "pg")
    assert f"target: embedded control plane at {tmp_path / 'state' / 'pg'}" in result.output


def test_reset_defaults_to_external_when_the_embedded_postgres_is_stopped(
    runner, jar_run, tmp_path, monkeypatch
):
    # A pgdata left by an earlier `qod serve` must not capture the default: qod admin never
    # starts Postgres, so a stopped embedded control plane is not a target.
    for key in ("QOD_PG_HOST", "QOD_PG_PORT", "QOD_PG_DBNAME"):
        monkeypatch.delenv(key, raising=False)
    _embedded_pg(tmp_path, monkeypatch, running=False)
    result = _invoke(runner, jar_run, input="pw\n")
    assert result.exit_code == 0, result.output
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "false"
    assert "target: Postgres localhost:5432/qod" in result.output


def test_reset_embedded_refuses_a_stopped_embedded_postgres(
    runner, jar_run, tmp_path, monkeypatch
):
    _embedded_pg(tmp_path, monkeypatch, running=False)
    result = _invoke(runner, jar_run, "--embedded", input="pw\n")
    assert result.exit_code == 1
    assert "is not running; start it with qod serve, then retry" in result.output
    assert "qod admin never starts Postgres" in result.output
    assert "cmd" not in jar_run


def test_reset_external_when_pg_host_configured(runner, jar_run, tmp_path, monkeypatch):
    _embedded_pg(tmp_path, monkeypatch, running=True)
    monkeypatch.setenv("QOD_PG_HOST", "db.internal")
    monkeypatch.setenv("QOD_PG_PORT", "6543")
    monkeypatch.setenv("QOD_PG_DBNAME", "cp")
    result = _invoke(runner, jar_run, input="pw\n")
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "false"
    assert "target: Postgres db.internal:6543/cp" in result.output


def test_reset_external_target_defaults(runner, jar_run, monkeypatch):
    for key in ("QOD_PG_HOST", "QOD_PG_PORT", "QOD_PG_DBNAME"):
        monkeypatch.delenv(key, raising=False)
    result = _invoke(runner, jar_run, input="pw\n")
    assert "target: Postgres localhost:5432/qod" in result.output


def test_reset_external_overrides_an_inherited_embedded_flag(
    runner, jar_run, tmp_path, monkeypatch
):
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True)
    monkeypatch.setenv("QOD_PG_EMBEDDED", "true")
    _invoke(runner, jar_run, "--external", input="pw\n")
    assert jar_run["env"]["QOD_PG_EMBEDDED"] == "false"


def test_reset_embedded_without_pgdata_refuses(runner, jar_run):
    result = _invoke(runner, jar_run, "--embedded", input="pw\n")
    assert result.exit_code == 1
    assert "nothing to reset; run qod serve first" in result.output
    assert "cmd" not in jar_run


def test_reset_refuses_an_empty_password(runner, jar_run):
    result = _invoke(runner, jar_run, input="\n")
    assert result.exit_code == 1
    assert "cmd" not in jar_run


def test_reset_propagates_the_jar_exit_code(runner, jar_run):
    jar_run["rc"] = 2
    result = _invoke(runner, jar_run, input="pw\n")
    assert result.exit_code == 2
