"""Shared plumbing for commands that launch the manager jar (demo, start)."""

import os
import re
import signal
import subprocess
import sys
import threading
from pathlib import Path

import typer

from .. import launcher
from .._manager_version import MANAGER_VERSION


def _exec(cmd: list[str], env: dict) -> None:
    if sys.platform == "win32":
        raise typer.Exit(subprocess.call(cmd, env=env))
    raise typer.Exit(_run_supervised(cmd, env))


def _run_supervised(cmd: list[str], env: dict, teardown=None) -> int:
    """Run the manager as a supervised child in its own session and turn
    Ctrl-C into `qod stop`.

    A plain exec would put the JVM (and, transitively, the spawn-script node
    wrappers and their duckdb children) in the terminal's foreground process
    group, so Ctrl-C SIGINTs the whole tree at once -- exactly the unclean
    teardown that orphans duckdb nodes on ports 21900+ (see "Things to avoid"
    in CLAUDE.md). Instead the child gets its own session, terminal signals
    land on this CLI alone, and the CLI answers with the stop sweep: SIGTERM
    manager + nodes, bounded wait, SIGKILL escalation. SIGTERM/SIGHUP on the
    CLI (kill, closed terminal) take the same path. Returns the manager's
    exit code, or 130 after an interrupt-triggered teardown."""
    if teardown is None:
        from .stop import perform_stop

        teardown = perform_stop
    # The JVM tree must never hold a terminal fd: something in it flips the
    # tty into raw mode during boot, and raw mode turns ISIG off - after
    # which Ctrl-C stops generating SIGINT at all and this supervisor never
    # hears it. stdin is closed off and stdout/stderr are piped through a
    # relay thread, so java and its nodes only ever see pipes.
    proc = subprocess.Popen(
        cmd,
        env=env,
        start_new_session=True,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )

    def _relay():
        # read1, never read: on a pipe, read(n) blocks until n bytes have
        # accumulated, which would hold the manager's startup banner (a few
        # hundred bytes) until 4 KB of later logging pushed it out. The manager
        # then looks hung while it is in fact serving. read1 hands over
        # whatever has arrived, so output appears as the manager emits it.
        assert proc.stdout is not None
        for chunk in iter(lambda: proc.stdout.read1(4096), b""):
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()

    relay = threading.Thread(target=_relay, daemon=True)
    relay.start()

    def _to_interrupt(signum, frame):
        raise KeyboardInterrupt

    old_term = signal.signal(signal.SIGTERM, _to_interrupt)
    old_hup = signal.signal(signal.SIGHUP, _to_interrupt) if hasattr(signal, "SIGHUP") else None
    try:
        return proc.wait()
    except KeyboardInterrupt:
        typer.echo("\ninterrupted; stopping the manager and its nodes (qod stop)...", err=True)
        try:
            teardown()
        except KeyboardInterrupt:
            typer.echo("second interrupt; killing the manager directly.", err=True)
        finally:
            # Safety net only: a completed teardown has already reaped the child.
            if proc.poll() is None:
                proc.kill()
            proc.wait()
        return 130
    finally:
        signal.signal(signal.SIGTERM, old_term)
        if old_hup is not None:
            signal.signal(signal.SIGHUP, old_hup)


def resolve_java() -> str:
    """A Java >= MIN_JAVA_MAJOR: `JAVA_BIN` verbatim when set (run-jar.sh
    convention, trusted), else the system one, else a Temurin JRE
    auto-provisioned into the cache (announced, never prompted)."""
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
    typer.echo(
        f"No Java {launcher.MIN_JAVA_MAJOR}+ found; downloading a Temurin "
        f"{launcher.JRE_MAJOR} JRE (about 50 MB, cached for next time)...",
        err=True,
    )
    return launcher.ensure_jre()


def _offline_fallback_version(reason: Exception) -> str:
    """The manager to run when the release lookup could not reach GitHub.

    Preference order: the release this CLI build pins (version.sbt, see
    _manager_version.py) when it is already cached, else the newest jar in the
    cache. Only cached versions are considered - offering an uncached one would
    just move the same network failure into `ensure_jar`. Exits when the cache
    is empty, since there is then nothing to run."""
    cached = launcher.cached_jar_versions()
    pinned = launcher.resolved_jar_version(MANAGER_VERSION)
    chosen = pinned if pinned in cached else launcher.newest_cached_jar()
    if chosen is None:
        typer.echo(
            f"could not resolve the latest release: {reason}\n"
            "No manager jar is cached either, so there is nothing to fall back to.\n"
            "Reconnect, or pass a local jar with --jar <path>.",
            err=True,
        )
        raise typer.Exit(1)
    typer.echo(
        f"could not reach GitHub ({reason}); starting the cached manager {chosen}.\n"
        f"Cached: {', '.join(cached)} (in {launcher.jar_cache_dir()}).",
        err=True,
    )
    return chosen


def resolve_jar(version: str | None) -> Path:
    """The manager jar to run: `version` if given, else the latest GitHub
    release. When the release lookup cannot reach the network, falls back to a
    jar already in the cache (see `_offline_fallback_version`) so an offline
    `uvx qod start` boots the manager the user already has instead of failing.
    An explicit `--version X.Y.Z` is never substituted. Floor-guarded,
    downloaded and verified."""
    if version and version != "latest":
        jar_version = version
    else:
        try:
            jar_version = launcher.latest_release_version()
        except Exception as e:
            jar_version = _offline_fallback_version(e)
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
        # Reached only when this exact version is neither cached nor
        # downloadable. An explicit pin is a contract, so we never silently
        # boot a different manager here; naming the cache lets the caller pick
        # one deliberately.
        cached = launcher.cached_jar_versions()
        alternatives = (
            f"\nAlready cached in {launcher.jar_cache_dir()}: {', '.join(cached)}"
            if cached
            else ""
        )
        typer.echo(
            f"could not download {launcher.jar_url(jar_version)}: {e}\n"
            f"If you have the jar locally, pass it with --jar <path>.{alternatives}",
            err=True,
        )
        raise typer.Exit(1)
