"""Typed Iceberg REST flags on `qod federation create`.

The body assertions read the bytes httpx actually put on the wire (respx records
the real request), the same mechanism test_database_create_defaults.py uses, so a
flag that never reaches `call()` cannot pass these tests.
"""

import json

import httpx
import pytest

BASE = "http://localhost:20900"


@pytest.fixture(autouse=True)
def api_key(monkeypatch):
    monkeypatch.setenv("QOD_API_KEY", "k")


def _route(respx_mock):
    """Mock the create endpoint. Registered even by the tests that expect the CLI to
    refuse before calling, so a lost guard surfaces as exit 0 rather than as a real
    request to whatever is listening on :20900."""
    return respx_mock.post(
        f"{BASE}/api/tenants/acme/tenant-dbs/db1/federated-sources"
    ).mock(return_value=httpx.Response(200, json={"alias": "x"}))


def _invoke(runner, *args):
    from qod_cli.main import app

    return runner.invoke(app, ["federation", "create", "acme", "db1", *args])


def _create(runner, respx_mock, *args):
    """Invoke `federation create` and return the JSON body it POSTed."""
    route = _route(respx_mock)
    result = _invoke(runner, *args)
    assert result.exit_code == 0, result.output
    # [-1]: respx reuses one route per pattern, so a test that creates twice
    # accumulates both calls on it.
    return json.loads(route.calls[-1].request.content.decode())


def test_iceberg_flags_assemble_config(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--alias", "sales_lake",
        "--type", "iceberg-rest",
        "--uri", "https://catalog.example.com/api/catalog",
        "--warehouse", "sales",
        "--auth", "oauth2",
        "--client-id", "{{secret.CID}}",
        "--client-secret", "{{secret.CSEC}}",
        "--oauth2-scope", "PRINCIPAL_ROLE:qod_reader",
    )
    assert body["sourceType"] == "iceberg_rest"
    assert body["config"]["uri"] == "https://catalog.example.com/api/catalog"
    assert body["config"]["warehouse"] == "sales"
    assert body["config"]["authType"] == "oauth2"
    assert body["config"]["clientId"] == "{{secret.CID}}"
    assert body["config"]["clientSecret"] == "{{secret.CSEC}}"
    assert body["config"]["oauth2Scope"] == "PRINCIPAL_ROLE:qod_reader"
    assert "setupSql" not in body


def test_oauth2_server_uri_grant_type_and_token_reach_the_config(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--alias", "l", "--type", "iceberg-rest", "--warehouse", "w",
        "--oauth2-server-uri", "https://idp.example.com/oauth/tokens",
        "--oauth2-grant-type", "client_credentials",
        "--token", "{{secret.TOK}}",
    )
    assert body["config"]["oauth2ServerUri"] == "https://idp.example.com/oauth/tokens"
    assert body["config"]["oauth2GrantType"] == "client_credentials"
    assert body["config"]["token"] == "{{secret.TOK}}"


def test_raw_config_flag_wins(runner, respx_mock):
    raw = json.dumps({"warehouse": "w", "authType": "none", "uri": "http://c"})
    body = _create(
        runner, respx_mock,
        "--alias", "l", "--type", "iceberg-rest", "--config", raw,
    )
    assert body["config"] == json.loads(raw)


def test_raw_config_ignores_the_ergonomic_flags(runner, respx_mock):
    raw = json.dumps({"warehouse": "raw_w", "authType": "none", "uri": "http://c"})
    body = _create(
        runner, respx_mock,
        "--alias", "l", "--type", "iceberg-rest", "--config", raw,
        "--warehouse", "flag_w",
    )
    assert body["config"] == json.loads(raw)


def test_endpoint_type_flag(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--alias", "g", "--type", "iceberg-rest",
        "--warehouse", "123456789012:cat", "--endpoint-type", "glue",
    )
    assert body["config"]["endpointType"] == "glue"


def test_read_only_flags_reach_the_body(runner, respx_mock):
    on = _create(
        runner, respx_mock,
        "--alias", "l", "--type", "iceberg-rest", "--warehouse", "w", "--read-only",
    )
    assert on["readOnly"] is True
    off = _create(
        runner, respx_mock,
        "--alias", "l", "--type", "iceberg-rest", "--warehouse", "w", "--no-read-only",
    )
    assert off["readOnly"] is False


def test_read_only_omitted_leaves_the_server_default(runner, respx_mock):
    body = _create(
        runner, respx_mock,
        "--alias", "l", "--type", "iceberg-rest", "--warehouse", "w",
    )
    assert "readOnly" not in body


def test_sql_source_still_works(runner, respx_mock):
    body = _create(runner, respx_mock, "--alias", "pg", "--setup-sql", "ATTACH 'x';")
    assert body["setupSql"] == "ATTACH 'x';"
    assert body["alias"] == "pg"
    assert body["disabled"] is False
    assert "config" not in body
    assert "sourceType" not in body


def test_iceberg_type_without_any_config_errors(runner, respx_mock):
    _route(respx_mock)
    result = _invoke(runner, "--alias", "l", "--type", "iceberg-rest")
    assert result.exit_code != 0
    assert "warehouse" in result.output.lower() or "config" in result.output.lower()


def test_setup_sql_with_iceberg_type_errors(runner, respx_mock):
    _route(respx_mock)
    result = _invoke(
        runner,
        "--alias", "l", "--type", "iceberg-rest",
        "--warehouse", "w", "--auth", "none", "--uri", "http://c",
        "--setup-sql", "ATTACH 'x';",
    )
    assert result.exit_code != 0


def test_sql_type_without_setup_sql_errors(runner, respx_mock):
    _route(respx_mock)
    result = _invoke(runner, "--alias", "l")
    assert result.exit_code != 0
    assert "setup-sql" in result.output.lower()


def test_unknown_type_errors(runner, respx_mock):
    _route(respx_mock)
    result = _invoke(runner, "--alias", "l", "--type", "hive")
    assert result.exit_code != 0
    assert "hive" in result.output


def test_malformed_config_json_errors(runner, respx_mock):
    _route(respx_mock)
    result = _invoke(runner, "--alias", "l", "--type", "iceberg-rest", "--config", "{not json")
    assert result.exit_code != 0
    assert "json" in result.output.lower()
