import json

import httpx
import pytest

BASE = "http://localhost:20900"


@pytest.fixture(autouse=True)
def token(monkeypatch):
    monkeypatch.setenv("QOD_TOKEN", "k")


def _create(runner, respx_mock, *args):
    from qod_cli.main import app

    route = respx_mock.post(f"{BASE}/api/database/create").mock(
        return_value=httpx.Response(200, json={"name": "acme_sales"})
    )
    result = runner.invoke(app, ["database", "create", *args])
    assert result.exit_code == 0, result.output
    return json.loads(route.calls[0].request.content.decode())


def test_duckdb_file_defaults_dbname_and_schema(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--tenant", "acme", "--name", "sales", "--kind", "duckdb-file",
        "--data-path", "/abs/sales.duckdb",
    )
    assert body["metastore"] == {"dbName": "sales", "schemaName": "main"}


def test_explicit_metastore_still_wins(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--tenant", "acme", "--name", "sales", "--kind", "duckdb-file",
        "--data-path", "/abs/sales.duckdb",
        "--metastore", "dbName=custom", "--metastore", "schemaName=analytics",
    )
    assert body["metastore"] == {"dbName": "custom", "schemaName": "analytics"}


def test_partial_metastore_fills_only_the_gap(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--tenant", "acme", "--name", "sales", "--kind", "duckdb-file",
        "--data-path", "/abs/sales.duckdb", "--metastore", "schemaName=analytics",
    )
    assert body["metastore"] == {"dbName": "sales", "schemaName": "analytics"}


def test_ducklake_metastore_is_untouched(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--tenant", "acme", "--name", "lake", "--kind", "ducklake",
        "--data-path", "/data/lake",
    )
    assert body["metastore"] == {}


def test_memory_metastore_is_untouched(runner, respx_mock):
    body = _create(runner, respx_mock, "--tenant", "acme", "--name", "mem", "--kind", "memory")
    assert body["metastore"] == {}
