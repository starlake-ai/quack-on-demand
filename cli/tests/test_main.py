from typer.testing import CliRunner

from qod_cli import __version__
from qod_cli.main import app

runner = CliRunner()


def test_version_flag():
    result = runner.invoke(app, ["--version"])
    assert result.exit_code == 0
    assert __version__ in result.output


def test_every_command_names_the_profile_file_on_stderr(isolated_env):
    result = runner.invoke(app, ["--profile", "prod", "config", "profiles"])
    path = isolated_env / "config.toml"
    assert f"qod: profile 'prod' from {path} (not found)" in result.stderr
    assert "qod: profile" not in result.stdout


def test_new_release_notice_on_a_terminal(monkeypatch):
    from qod_cli import launcher, main

    monkeypatch.setattr(main, "_stderr_is_tty", lambda: True)
    monkeypatch.setattr(launcher, "cached_release_hint", lambda current: "note: qod 99.0.0 is available")
    result = runner.invoke(app, ["config", "profiles"])
    assert "note: qod 99.0.0 is available" in result.stderr
    assert "99.0.0" not in result.stdout


def test_new_release_notice_never_breaks_a_command(monkeypatch):
    from qod_cli import launcher, main

    def boom(current):
        raise RuntimeError("offline")

    monkeypatch.setattr(main, "_stderr_is_tty", lambda: True)
    monkeypatch.setattr(launcher, "cached_release_hint", boom)
    result = runner.invoke(app, ["config", "profiles"])
    assert result.exit_code == 0


def test_new_release_notice_is_quiet_off_a_terminal(monkeypatch):
    from qod_cli import launcher

    called = []
    monkeypatch.setattr(launcher, "cached_release_hint", lambda current: called.append(1))
    runner.invoke(app, ["config", "profiles"])
    assert not called
