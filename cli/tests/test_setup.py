import json

from qod_cli.config import load_start_env


def test_setup_flag_writes_start_env(runner):
    from qod_cli.main import app

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


def test_setup_show_redacts_secrets(runner):
    from qod_cli.main import app

    runner.invoke(
        app, ["setup", "--pg-password", "s3cret-value", "--api-key", "topsecret", "--non-interactive"]
    )
    result = runner.invoke(app, ["setup", "--show"])
    assert result.exit_code == 0, result.output
    assert "s3cret-value" not in result.output
    assert "topsecret" not in result.output


def test_setup_show_json(runner):
    from qod_cli.main import app

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
