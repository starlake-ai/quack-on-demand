import json

from qod_cli.config import load_start_env


def _stub_check_ok(monkeypatch, detail="connection check ok (stub)"):
    from qod_cli.commands import setup as setup_mod

    calls = []

    def fake(merged):
        calls.append(dict(merged))
        return True, detail

    monkeypatch.setattr(setup_mod, "_check_connection", fake)
    return calls


def test_setup_flag_writes_start_env(runner, monkeypatch):
    from qod_cli.main import app

    _stub_check_ok(monkeypatch)
    result = runner.invoke(
        app,
        [
            "setup",
            "--pg-host",
            "db.internal",
            "--pg-password",
            "s3cret",
            "--no-tls",
        ],
    )
    assert result.exit_code == 0, result.output
    assert load_start_env() == {
        "QOD_PG_HOST": "db.internal",
        "QOD_PG_PASSWORD": "s3cret",
        "PROXY_TLS_ENABLED": "false",
    }
    assert "qod start" in result.output


def test_setup_set_and_unset(runner):
    from qod_cli.main import app

    result = runner.invoke(app, ["setup", "--set", "QOD_MIN_PORT=21900", "--non-interactive"])
    assert result.exit_code == 0, result.output
    assert load_start_env() == {"QOD_MIN_PORT": "21900"}

    result = runner.invoke(app, ["setup", "--unset", "QOD_MIN_PORT", "--non-interactive"])
    assert result.exit_code == 0, result.output
    assert load_start_env() == {}


def test_setup_set_requires_key_equals_value(runner):
    from qod_cli.main import app

    result = runner.invoke(app, ["setup", "--set", "not-a-pair", "--non-interactive"])
    assert result.exit_code != 0


def test_setup_show_when_empty(runner):
    from qod_cli.main import app

    result = runner.invoke(app, ["setup", "--show"])
    assert result.exit_code == 0, result.output
    assert "no start config stored" in result.output


def test_setup_show_redacts_secrets(runner, monkeypatch):
    from qod_cli.main import app

    _stub_check_ok(monkeypatch)
    runner.invoke(
        app, ["setup", "--pg-password", "s3cret-value", "--api-key", "topsecret", "--non-interactive"]
    )
    result = runner.invoke(app, ["setup", "--show"])
    assert result.exit_code == 0, result.output
    assert "s3cret-value" not in result.output
    assert "topsecret" not in result.output


def test_setup_show_json(runner, monkeypatch):
    from qod_cli.main import app

    _stub_check_ok(monkeypatch)
    runner.invoke(app, ["setup", "--pg-host", "db.internal", "--non-interactive"])
    result = runner.invoke(app, ["--json", "setup", "--show"])
    assert result.exit_code == 0, result.output
    rows = json.loads(result.stdout)
    assert {"key": "QOD_PG_HOST", "value": "db.internal"} in rows


def test_setup_non_interactive_with_nothing_fails(runner):
    from qod_cli.main import app

    result = runner.invoke(app, ["setup", "--non-interactive"])
    assert result.exit_code == 1
    assert load_start_env() == {}


def test_setup_interactive_prompts_and_saves(runner, monkeypatch):
    from qod_cli.commands import setup as setup_cmd
    from qod_cli.main import app

    # CliRunner rebinds sys.stdin to its own stream on every invoke(), so
    # patching the pre-invoke object's isatty is a no-op; patch the helper
    # setup.py calls instead to force the interactive branch.
    monkeypatch.setattr(setup_cmd, "_is_interactive", lambda: True)
    _stub_check_ok(monkeypatch)
    answers = "\n".join(
        [
            "db.internal",  # host
            "",  # port -> default 5432
            "",  # user -> default postgres
            "s3cret",  # password
            "",  # dbname -> default qod
            "",  # admin username -> default
            "",  # admin password -> default
            "",  # api key -> default blank
            "",  # auth -> default true
            "",  # tls -> default true
        ]
    )
    result = runner.invoke(app, ["setup"], input=answers + "\n")
    assert result.exit_code == 0, result.output
    stored = load_start_env()
    assert stored["QOD_PG_HOST"] == "db.internal"
    assert stored["QOD_PG_PASSWORD"] == "s3cret"
    assert stored["QOD_PG_PORT"] == "5432"
    assert stored["PROXY_TLS_ENABLED"] == "true"


def test_setup_check_failure_non_interactive_saves_nothing(runner, monkeypatch):
    from qod_cli.commands import setup as setup_mod
    from qod_cli.main import app

    monkeypatch.setattr(
        setup_mod, "_check_connection", lambda merged: (False, "cannot reach db:5432 (refused)")
    )
    result = runner.invoke(
        app, ["setup", "--pg-host", "db.internal", "--non-interactive"]
    )
    assert result.exit_code == 1
    assert "connection check failed" in result.output
    assert "--skip-checks" in result.output
    assert load_start_env() == {}


def test_setup_skip_checks_saves_without_probing(runner, monkeypatch):
    from qod_cli.commands import setup as setup_mod
    from qod_cli.main import app

    def boom(merged):
        raise AssertionError("check must not run under --skip-checks")

    monkeypatch.setattr(setup_mod, "_check_connection", boom)
    result = runner.invoke(
        app, ["setup", "--pg-host", "db.internal", "--skip-checks", "--non-interactive"]
    )
    assert result.exit_code == 0, result.output
    assert load_start_env() == {"QOD_PG_HOST": "db.internal"}


def test_setup_non_connection_set_skips_check(runner, monkeypatch):
    from qod_cli.commands import setup as setup_mod
    from qod_cli.main import app

    def boom(merged):
        raise AssertionError("check must not run for non-connection keys")

    monkeypatch.setattr(setup_mod, "_check_connection", boom)
    result = runner.invoke(app, ["setup", "--set", "QOD_MIN_PORT=21900", "--non-interactive"])
    assert result.exit_code == 0, result.output
    assert load_start_env() == {"QOD_MIN_PORT": "21900"}


def test_setup_check_uses_merged_effective_config(runner, monkeypatch):
    from qod_cli.main import app

    _stub_check_ok(monkeypatch)
    runner.invoke(app, ["setup", "--pg-host", "stored.host", "--non-interactive"])
    calls = _stub_check_ok(monkeypatch)
    result = runner.invoke(app, ["setup", "--pg-port", "6432", "--non-interactive"])
    assert result.exit_code == 0, result.output
    assert calls and calls[-1]["QOD_PG_HOST"] == "stored.host"
    assert calls[-1]["QOD_PG_PORT"] == "6432"


def test_check_connection_tcp_failure(monkeypatch):
    import socket as socket_mod

    from qod_cli.commands.setup import _check_connection

    def refuse(addr, timeout=None):
        raise OSError("connection refused")

    monkeypatch.setattr(socket_mod, "create_connection", refuse)
    ok, detail = _check_connection({"QOD_PG_HOST": "db", "QOD_PG_PORT": "5432"})
    assert not ok
    assert "cannot reach db:5432" in detail


def test_check_connection_no_psql_reports_unverified(monkeypatch):
    import shutil as shutil_mod
    import socket as socket_mod
    from contextlib import nullcontext

    from qod_cli.commands.setup import _check_connection

    monkeypatch.setattr(socket_mod, "create_connection", lambda a, timeout=None: nullcontext())
    monkeypatch.setattr(shutil_mod, "which", lambda name: None)
    ok, detail = _check_connection({"QOD_PG_HOST": "db"})
    assert ok
    assert "credentials unverified" in detail


def test_check_connection_psql_login_failure(monkeypatch):
    import shutil as shutil_mod
    import socket as socket_mod
    import subprocess as subprocess_mod
    from contextlib import nullcontext

    from qod_cli.commands.setup import _check_connection

    monkeypatch.setattr(socket_mod, "create_connection", lambda a, timeout=None: nullcontext())
    monkeypatch.setattr(shutil_mod, "which", lambda name: "/usr/bin/psql")

    class Proc:
        returncode = 2
        stdout = ""
        stderr = 'psql: error: FATAL:  password authentication failed for user "postgres"'

    captured_env = {}

    def fake_run(argv, env=None, **kw):
        captured_env.update(env or {})
        assert "PGPASSWORD" in env
        assert all("s3cret" not in a for a in argv)
        return Proc()

    monkeypatch.setattr(subprocess_mod, "run", fake_run)
    ok, detail = _check_connection({"QOD_PG_HOST": "db", "QOD_PG_PASSWORD": "s3cret"})
    assert not ok
    assert "login failed" in detail
    assert captured_env["PGPASSWORD"] == "s3cret"
