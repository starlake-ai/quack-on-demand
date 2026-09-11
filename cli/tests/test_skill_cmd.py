from pathlib import Path

from typer.testing import CliRunner

from qod_cli.commands import skill_cmd
from qod_cli.main import app


def test_install_into_explicit_dir(tmp_path):
    r = CliRunner().invoke(app, ["skill", "install", "--dir", str(tmp_path)])
    assert r.exit_code == 0, r.output
    installed = tmp_path / "quack-on-demand" / "SKILL.md"
    assert installed.is_file()
    assert "name: quack-on-demand" in installed.read_text()


def test_install_overwrites_stale_copy(tmp_path):
    target = tmp_path / "quack-on-demand"
    target.mkdir(parents=True)
    (target / "SKILL.md").write_text("stale")
    r = CliRunner().invoke(app, ["skill", "install", "--dir", str(tmp_path)])
    assert r.exit_code == 0, r.output
    assert (target / "SKILL.md").read_text() != "stale"


def test_install_refuses_symlinked_target(tmp_path):
    real = tmp_path / "checkout-skill"
    real.mkdir()
    skills_dir = tmp_path / "skills"
    skills_dir.mkdir()
    (skills_dir / "quack-on-demand").symlink_to(real)
    r = CliRunner().invoke(app, ["skill", "install", "--dir", str(skills_dir)])
    assert r.exit_code == 1
    assert "symlink" in r.output
    assert not (real / "SKILL.md").exists()


def test_install_non_tty_defaults_to_claude(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    r = CliRunner().invoke(app, ["skill", "install"])
    assert r.exit_code == 0, r.output
    assert (tmp_path / ".claude" / "skills" / "quack-on-demand" / "SKILL.md").is_file()
    assert not (tmp_path / ".copilot").exists()
    assert not (tmp_path / ".gemini").exists()


def test_install_tty_prompts_for_platform(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    monkeypatch.setattr(skill_cmd, "_stdin_isatty", lambda: True)
    r = CliRunner().invoke(app, ["skill", "install"], input="gemini\n")
    assert r.exit_code == 0, r.output
    assert "Install the skill for which LLM?" in r.output
    assert (tmp_path / ".gemini" / "skills" / "quack-on-demand" / "SKILL.md").is_file()
    assert not (tmp_path / ".claude").exists()


def test_install_tty_prompt_default_is_claude(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    monkeypatch.setattr(skill_cmd, "_stdin_isatty", lambda: True)
    r = CliRunner().invoke(app, ["skill", "install"], input="\n")
    assert r.exit_code == 0, r.output
    assert (tmp_path / ".claude" / "skills" / "quack-on-demand" / "SKILL.md").is_file()


def test_install_platform_flag_skips_prompt(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    monkeypatch.setattr(skill_cmd, "_stdin_isatty", lambda: True)
    r = CliRunner().invoke(app, ["skill", "install", "--platform", "copilot"])
    assert r.exit_code == 0, r.output
    assert "which LLM" not in r.output
    assert (tmp_path / ".copilot" / "skills" / "quack-on-demand" / "SKILL.md").is_file()


def test_install_platform_all_and_comma_list(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    r = CliRunner().invoke(app, ["skill", "install", "--platform", "all"])
    assert r.exit_code == 0, r.output
    for p in ("claude", "copilot", "gemini"):
        assert (tmp_path / f".{p}" / "skills" / "quack-on-demand" / "SKILL.md").is_file()

    other = tmp_path / "other-home"
    monkeypatch.setattr(Path, "home", lambda: other)
    r = CliRunner().invoke(app, ["skill", "install", "--platform", "claude,gemini"])
    assert r.exit_code == 0, r.output
    assert (other / ".claude" / "skills" / "quack-on-demand" / "SKILL.md").is_file()
    assert (other / ".gemini" / "skills" / "quack-on-demand" / "SKILL.md").is_file()
    assert not (other / ".copilot").exists()


def test_install_rejects_unknown_platform(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    r = CliRunner().invoke(app, ["skill", "install", "--platform", "chatgpt"])
    assert r.exit_code != 0
    assert "unknown platform" in r.output


def test_install_project_targets_cwd(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    r = CliRunner().invoke(app, ["skill", "install", "--project", "--platform", "claude"])
    assert r.exit_code == 0, r.output
    assert (tmp_path / ".claude" / "skills" / "quack-on-demand" / "SKILL.md").is_file()


def test_project_and_dir_are_exclusive(tmp_path):
    r = CliRunner().invoke(app, ["skill", "install", "--project", "--dir", str(tmp_path)])
    assert r.exit_code != 0


def test_platform_and_dir_are_exclusive(tmp_path):
    r = CliRunner().invoke(app, ["skill", "install", "--platform", "claude", "--dir", str(tmp_path)])
    assert r.exit_code != 0


def test_path_prints_bundled_location():
    r = CliRunner().invoke(app, ["skill", "path"])
    assert r.exit_code == 0, r.output
    assert "quack-on-demand" in r.output
