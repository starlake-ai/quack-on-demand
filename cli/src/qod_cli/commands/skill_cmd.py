"""qod skill: install the bundled agent skill.

The wheel ships a copy of the operator skill (the canonical file lives at
plugins/qod/skills/quack-on-demand/SKILL.md in the repo; a freshness test
pins the two together). `qod skill install` copies it where the user's LLM
tooling discovers skills - Claude Code, GitHub Copilot, and Gemini CLI all
read `~/.<platform>/skills/<name>/` (same convention as the starlake-skills
installer) - so a pip/uv user gets the skill without cloning the repo. The
Claude Code plugin (/plugin marketplace add starlake-ai/quack-on-demand) is
the other distribution channel and manages its own updates.
"""

import shutil
import sys
from importlib import resources
from pathlib import Path

import typer

app = typer.Typer(help="Agent skill bundled with this CLI (Claude Code, Copilot, Gemini).")

SKILL_NAME = "quack-on-demand"
PLATFORMS = ("claude", "copilot", "gemini")


def _bundled_skill():
    return resources.files("qod_cli") / "skills" / SKILL_NAME


def _stdin_isatty() -> bool:
    # Module-level seam: CliRunner swaps sys.stdin during invoke, so tests
    # patch this helper rather than the stdin object.
    return sys.stdin.isatty()


def _resolve_platforms(flag_values: list[str] | None) -> list[str]:
    """Expand/validate --platform values; prompt when omitted on a terminal
    (non-tty runs default to claude so scripts stay non-interactive)."""
    if flag_values:
        raw = [p.strip() for v in flag_values for p in v.split(",") if p.strip()]
    elif _stdin_isatty():
        answer = typer.prompt(
            "Install the skill for which LLM? (claude, copilot, gemini, all)",
            default="claude",
        )
        raw = [p.strip().lower() for p in answer.split(",") if p.strip()]
    else:
        raw = ["claude"]
    if "all" in raw:
        return list(PLATFORMS)
    bad = [p for p in raw if p not in PLATFORMS]
    if bad:
        raise typer.BadParameter(
            f"unknown platform(s) {', '.join(bad)}; pick from {', '.join(PLATFORMS)} or all"
        )
    return list(dict.fromkeys(raw))


def _install_into(src: Path, target: Path) -> None:
    if target.is_symlink():
        # A symlinked target usually points into a checkout; copying through it
        # would silently rewrite that working tree.
        typer.echo(
            f"error: {target} is a symlink; refusing to overwrite through it. "
            "Remove the symlink first, or pass --dir for a different target.",
            err=True,
        )
        raise typer.Exit(1)
    target.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        src, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__", "*.pyc")
    )
    typer.echo(f"installed skill '{SKILL_NAME}' -> {target}")


@app.command()
def install(
    platform: list[str] = typer.Option(
        None,
        "--platform",
        help="LLM to install for: claude|copilot|gemini|all (repeatable, or comma-separated). "
        "Prompted for interactively when omitted; non-tty defaults to claude.",
    ),
    project: bool = typer.Option(
        False,
        "--project",
        help="Install into ./.{platform}/skills (this project only) instead of ~/.{platform}/skills.",
    ),
    directory: Path = typer.Option(
        None, "--dir", help="Install into this skills directory instead of a platform default."
    ),
):
    """Copy the bundled quack-on-demand skill where the chosen LLM discovers it.

    The default target is the per-user skills directory of the chosen platform
    (~/.claude/skills, ~/.copilot/skills, ~/.gemini/skills), so the skill is
    available in every project; new sessions pick it up automatically. Re-run
    after a CLI upgrade to refresh the copy.
    """
    if directory is not None and project:
        raise typer.BadParameter("pass at most one of --project / --dir")
    if directory is not None and platform:
        raise typer.BadParameter("--dir names an exact target; it excludes --platform")
    if directory is not None:
        targets = [directory / SKILL_NAME]
    else:
        root = Path.cwd() if project else Path.home()
        targets = [
            root / f".{p}" / "skills" / SKILL_NAME for p in _resolve_platforms(platform)
        ]
    with resources.as_file(_bundled_skill()) as src:
        if not (src / "SKILL.md").is_file():
            typer.echo("error: this build of the qod package does not bundle the skill.", err=True)
            raise typer.Exit(1)
        for target in targets:
            _install_into(src, target)


@app.command()
def path():
    """Print the bundled skill's location inside the installed package."""
    with resources.as_file(_bundled_skill()) as src:
        typer.echo(str(src))
