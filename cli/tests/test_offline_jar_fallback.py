"""Offline jar resolution: with no network, `qod start` must boot the manager
already in the cache instead of failing.

The uvx path makes this load-bearing: `uvx qod start` resolves the manager jar
from GitHub on every run, so a laptop on a plane (or any DNS-less network) used
to hard-fail even with a perfectly good jar sitting in the cache.
"""

import httpx
import pytest
import typer

from qod_cli import launcher
from qod_cli.commands import _launch


@pytest.fixture(autouse=True)
def offline(monkeypatch):
    """Every test in this file runs with no network at all. Faithful to the
    scenario, and it keeps a resolution bug from quietly pulling a 300 MB jar
    off GitHub during the suite."""

    def boom(*args, **kwargs):
        raise httpx.ConnectError("no route to host")

    monkeypatch.setattr(httpx, "get", boom)
    monkeypatch.setattr(httpx, "stream", boom)


def _seed(cache_dir, *versions):
    for v in versions:
        (cache_dir / launcher.jar_name(v)).write_bytes(b"jar")
    return cache_dir


# ---- newest_cached_jar ------------------------------------------------


def test_newest_cached_jar_picks_the_highest_version(tmp_path):
    _seed(tmp_path, "0.5.3", "0.6.9", "0.6.2")
    assert launcher.newest_cached_jar(cache_dir=tmp_path) == "0.6.9"


def test_newest_cached_jar_orders_numerically_not_lexicographically(tmp_path):
    _seed(tmp_path, "0.9.9", "0.10.0")
    assert launcher.newest_cached_jar(cache_dir=tmp_path) == "0.10.0"


def test_newest_cached_jar_is_none_when_cache_is_empty(tmp_path):
    assert launcher.newest_cached_jar(cache_dir=tmp_path) is None


def test_newest_cached_jar_honors_jar_cache_dir(tmp_path, monkeypatch):
    _seed(tmp_path, "0.6.1")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    assert launcher.newest_cached_jar() == "0.6.1"


def test_newest_cached_jar_ignores_partial_downloads(tmp_path):
    _seed(tmp_path, "0.6.1")
    (tmp_path / "quack-on-demand-assembly-0.7.0.jar.partial").write_bytes(b"half")
    assert launcher.newest_cached_jar(cache_dir=tmp_path) == "0.6.1"


# ---- resolve_jar offline ----------------------------------------------


def test_offline_dev_build_falls_back_to_the_newest_cached_jar(tmp_path, monkeypatch):
    # A dev CLI build has no pinned release, so before this fallback existed
    # an offline `qod start` died with "could not resolve the latest release".
    _seed(tmp_path, "0.6.2", "0.6.9")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.6.10.dev0")
    assert _launch.resolve_jar(None) == tmp_path / launcher.jar_name("0.6.9")


def test_offline_version_latest_falls_back_instead_of_raising(tmp_path, monkeypatch):
    # `--version latest` / QOD_VERSION=latest used to escape resolve_jar's
    # try/except entirely and surface a raw httpx traceback.
    _seed(tmp_path, "0.6.9")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    assert _launch.resolve_jar("latest") == tmp_path / launcher.jar_name("0.6.9")


def test_offline_prefers_the_pinned_release_when_it_is_cached(tmp_path, monkeypatch):
    # A released CLI pins its matching manager; honour that over a newer jar
    # that happens to be lying in the cache.
    _seed(tmp_path, "0.6.7", "0.6.9")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.6.7")
    assert _launch.resolve_jar(None) == tmp_path / launcher.jar_name("0.6.7")


def test_offline_pinned_release_not_cached_uses_newest_cached(tmp_path, monkeypatch):
    _seed(tmp_path, "0.6.2")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.6.7")
    assert _launch.resolve_jar(None) == tmp_path / launcher.jar_name("0.6.2")


def test_offline_with_an_empty_cache_still_fails_with_guidance(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.6.10.dev0")
    with pytest.raises(typer.Exit) as exc:
        _launch.resolve_jar(None)
    assert exc.value.exit_code == 1
    err = capsys.readouterr().err
    assert "--jar" in err


def test_offline_fallback_refuses_a_jar_predating_the_demo(tmp_path, monkeypatch):
    # The MIN_DEMO_VERSION floor guard still applies to a cached fallback.
    _seed(tmp_path, "0.3.7")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.6.10.dev0")
    with pytest.raises(typer.Exit):
        _launch.resolve_jar(None)


def test_explicit_version_offline_does_not_silently_substitute(tmp_path, monkeypatch, capsys):
    # An explicit pin is a contract: never boot a different manager behind the
    # user's back. Fail, but name what is cached.
    _seed(tmp_path, "0.6.9")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))

    with pytest.raises(typer.Exit):
        _launch.resolve_jar("0.6.2")
    out = capsys.readouterr()
    assert "0.6.9" in (out.err + out.out)
