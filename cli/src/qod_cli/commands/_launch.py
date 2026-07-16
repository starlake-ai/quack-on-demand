"""Shared plumbing for commands that launch the manager jar (demo, start)."""

import os
import re
import subprocess
import sys
from pathlib import Path

import typer

from .. import launcher
from .._manager_version import MANAGER_VERSION


def _exec(cmd: list[str], env: dict) -> None:
    if sys.platform == "win32":
        raise typer.Exit(subprocess.call(cmd, env=env))
    os.execvpe(cmd[0], cmd, env)


def resolve_java(yes: bool) -> str:
    """A Java >= MIN_JAVA_MAJOR: `JAVA_BIN` verbatim when set (run-jar.sh
    convention, trusted), else the system one, else a Temurin JRE
    auto-provisioned into the cache (prompted on a TTY unless `yes`)."""
    java_bin = os.environ.get("JAVA_BIN")
    if java_bin:
        return java_bin
    java = launcher.find_java()
    if java is not None:
        return java
    if launcher.is_musl():
        typer.echo(
            f"No Java {launcher.MIN_JAVA_MAJOR}+ found, and this system uses musl libc, "
            "which Temurin builds\n"
            "do not support. Install a system JRE instead, e.g.: apk add openjdk21-jre",
            err=True,
        )
        raise typer.Exit(1)
    prompt = (
        f"No Java {launcher.MIN_JAVA_MAJOR}+ found. Download a Temurin "
        f"{launcher.JRE_MAJOR} JRE (about 50 MB, cached)?"
    )
    if not yes and sys.stdin.isatty():
        typer.confirm(prompt, abort=True)
    else:
        typer.echo(
            f"No Java {launcher.MIN_JAVA_MAJOR}+ found; downloading a Temurin "
            f"{launcher.JRE_MAJOR} JRE (cached for next time)...",
            err=True,
        )
    return launcher.ensure_jre()


def resolve_jar(version: str | None) -> Path:
    """The manager jar to run: `version` if given, else the latest GitHub
    release, falling back to the manager release stamped into this CLI build
    (from version.sbt, see _manager_version.py) when the lookup fails, so a
    cached jar still boots offline. Floor-guarded, downloaded and verified."""
    if version:
        jar_version = launcher.latest_release_version() if version == "latest" else version
    else:
        try:
            jar_version = launcher.latest_release_version()
        except Exception as e:
            fallback = launcher.resolved_jar_version(MANAGER_VERSION)
            if fallback is None:
                typer.echo(f"could not resolve the latest release: {e}", err=True)
                raise typer.Exit(1)
            typer.echo(
                f"could not resolve the latest release ({e}); "
                f"falling back to this build's manager release {fallback}.",
                err=True,
            )
            jar_version = fallback
    if not re.fullmatch(r"\d+(\.\d+)*", jar_version):
        typer.echo(
            f"'{jar_version}' is not a release version. QOD_VERSION=BUILD/LOCAL are "
            "run-jar.sh conventions; pass a local jar with --jar <path> instead.",
            err=True,
        )
        raise typer.Exit(1)
    if launcher.version_lt(jar_version, launcher.MIN_DEMO_VERSION):
        typer.echo(
            f"release {jar_version} predates the standalone launcher; the first release "
            f"with `demo`/`start` support is {launcher.MIN_DEMO_VERSION}.",
            err=True,
        )
        raise typer.Exit(1)
    try:
        return launcher.ensure_jar(jar_version)
    except launcher.IntegrityError as e:
        typer.echo(f"refusing to run: {e}", err=True)
        raise typer.Exit(1)
    except typer.Exit:
        raise
    except Exception as e:
        typer.echo(
            f"could not download {launcher.jar_url(jar_version)}: {e}\n"
            "If you have the jar locally, pass it with --jar <path>.",
            err=True,
        )
        raise typer.Exit(1)
