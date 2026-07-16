import os

import pytest
from typer.testing import CliRunner


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
    monkeypatch.setenv("QOD_CONFIG_FILE", str(tmp_path / "config.toml"))
    return tmp_path


@pytest.fixture
def runner():
    return CliRunner()
