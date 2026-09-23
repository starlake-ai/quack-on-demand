import os

# Typer forces rich's terminal mode whenever GITHUB_ACTIONS (or FORCE_COLOR / PY_COLORS) is set,
# and rich then styles `--setup-sql` as three separately coloured tokens, so an assertion on
# `result.output` no longer finds the substring on CI while passing locally. The knob is read
# once, at typer import time, so it must be set before anything imports typer.
os.environ.setdefault("_TYPER_FORCE_DISABLE_TERMINAL", "1")

import pytest  # noqa: E402
from typer.testing import CliRunner  # noqa: E402


@pytest.fixture(autouse=True)
def no_pypi_staleness_check(monkeypatch):
    """`serve()`/`start()` call launcher.newer_release_hint() at the top, which
    hits the real PyPI API; stub it out everywhere so tests never depend on
    network access. Tests exercising the hint itself (test_launcher.py) import
    the function directly, before this fixture runs, so they are unaffected;
    tests exercising the serve()/start() call site override this per-test."""
    from qod_cli import launcher

    monkeypatch.setattr(launcher, "newer_release_hint", lambda *a, **kw: None)


@pytest.fixture(autouse=True)
def isolated_env(tmp_path, monkeypatch):
    for var in list(os.environ):
        if var.startswith("QOD_"):
            monkeypatch.delenv(var, raising=False)
    # run-jar.sh convention vars honored by the demo/start launchers.
    for var in ("JAVA_OPTS", "JAVA_BIN", "JAR_CACHE_DIR", "DUCKDB_VERSION",
                "DUCKDB_CACHE_DIR", "LOAD_TPC", "LOAD_TPCH", "LOAD_TPCDS",
                "LOAD_SSB", "DEMO", "NUKE"):
        monkeypatch.delenv(var, raising=False)
    # A real exported AWS key in the running shell broke a credential test (#100):
    # `qod serve`'s s3 fallback reads these, so they must be isolated like QOD_* is.
    for var in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
                "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_PROFILE"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("QOD_CONFIG_FILE", str(tmp_path / "config.toml"))
    return tmp_path


@pytest.fixture
def runner():
    return CliRunner()
