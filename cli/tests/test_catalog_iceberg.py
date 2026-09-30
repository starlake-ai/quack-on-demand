"""`--iceberg ALIAS` on `qod catalog` commands, targeting an attached iceberg_rest
federated source instead of the tenant-db's own DuckLake catalog.

The URL/params assertions read the real request respx recorded, the same mechanism
test_federation_iceberg.py uses, so a flag that never reaches `call()` cannot pass
these tests.
"""

import httpx
import pytest

from qod_cli.main import app

BASE = "http://localhost:20900"


@pytest.fixture(autouse=True)
def api_key(monkeypatch):
    monkeypatch.setenv("QOD_API_KEY", "k")


def _invoke(runner, *args):
    return runner.invoke(app, ["catalog", *args])


def _mock(respx_mock, url, json=None):
    return respx_mock.get(url).mock(return_value=httpx.Response(200, json=json or {}))


# ---------------------------------------------------------------------------
# schemas
# ---------------------------------------------------------------------------


def test_schemas_without_iceberg_hits_ducklake_path(runner, respx_mock):
    route = _mock(respx_mock, f"{BASE}/api/catalog/tenant/acme/database/db1/schemas", json=[])
    result = _invoke(runner, "schemas", "acme", "db1")
    assert result.exit_code == 0, result.output
    assert route.called


def test_schemas_with_iceberg_hits_iceberg_path(runner, respx_mock):
    route = _mock(
        respx_mock, f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas", json=[]
    )
    result = _invoke(runner, "schemas", "acme", "db1", "--iceberg", "ice")
    assert result.exit_code == 0, result.output
    assert route.called


# ---------------------------------------------------------------------------
# tables
# ---------------------------------------------------------------------------


def test_tables_with_iceberg_hits_iceberg_path(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables",
        json=[],
    )
    result = _invoke(runner, "tables", "acme", "db1", "main", "--iceberg", "ice")
    assert result.exit_code == 0, result.output
    assert route.called


# ---------------------------------------------------------------------------
# describe
# ---------------------------------------------------------------------------


def test_describe_with_iceberg_hits_iceberg_path(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders",
        json={},
    )
    result = _invoke(runner, "describe", "acme", "db1", "main", "orders", "--iceberg", "ice")
    assert result.exit_code == 0, result.output
    assert route.called


@pytest.mark.parametrize("flag,value", [("--as-of", "1"), ("--as-of-tag", "v1"), ("--as-of-ts", "2026-01-01T00:00:00Z")])
def test_describe_refuses_as_of_selectors_with_iceberg(runner, respx_mock, flag, value):
    _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders",
        json={},
    )
    result = _invoke(runner, "describe", "acme", "db1", "main", "orders", "--iceberg", "ice", flag, value)
    assert result.exit_code != 0
    assert "not supported with --iceberg" in result.output


def test_describe_without_iceberg_still_validates_as_of_digits(runner, respx_mock):
    _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/schemas/main/tables/orders",
        json={},
    )
    result = _invoke(runner, "describe", "acme", "db1", "main", "orders", "--as-of", "not-a-number")
    assert result.exit_code != 0


def test_describe_without_iceberg_sends_as_of_as_integer(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/schemas/main/tables/orders",
        json={},
    )
    result = _invoke(runner, "describe", "acme", "db1", "main", "orders", "--as-of", "42")
    assert result.exit_code == 0, result.output
    assert route.calls.last.request.url.params["asOf"] == "42"


# ---------------------------------------------------------------------------
# history
# ---------------------------------------------------------------------------


def test_history_with_iceberg_sends_only_limit_before_operation(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders/history",
        json={},
    )
    result = _invoke(
        runner,
        "history", "acme", "db1", "main", "orders", "--iceberg", "ice",
        "--limit", "10", "--before", "1234567890123456789", "--operation", "append",
    )
    assert result.exit_code == 0, result.output
    sent = route.calls.last.request.url.params
    assert sent["limit"] == "10"
    assert sent["before"] == "1234567890123456789"
    assert sent["operation"] == "append"
    assert "from" not in sent
    assert "to" not in sent
    assert "author" not in sent


@pytest.mark.parametrize("flag,value", [("--from", "1"), ("--to", "2"), ("--author", "bob")])
def test_history_refuses_from_to_author_with_iceberg(runner, respx_mock, flag, value):
    _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders/history",
        json={},
    )
    result = _invoke(
        runner, "history", "acme", "db1", "main", "orders", "--iceberg", "ice", flag, value,
    )
    assert result.exit_code != 0
    assert "not supported with --iceberg" in result.output


def test_history_without_iceberg_still_sends_integer_before(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/schemas/main/tables/orders/history",
        json={},
    )
    result = _invoke(runner, "history", "acme", "db1", "main", "orders", "--before", "99")
    assert result.exit_code == 0, result.output
    assert route.calls.last.request.url.params["before"] == "99"


def test_history_without_iceberg_rejects_non_digit_before(runner, respx_mock):
    _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/schemas/main/tables/orders/history",
        json={},
    )
    result = _invoke(runner, "history", "acme", "db1", "main", "orders", "--before", "abc")
    assert result.exit_code != 0


# ---------------------------------------------------------------------------
# preview
# ---------------------------------------------------------------------------


def test_preview_with_iceberg_sends_as_of_as_string(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders/preview",
        json={},
    )
    result = _invoke(
        runner, "preview", "acme", "db1", "main", "orders", "--iceberg", "ice",
        "--as-of", "1234567890123456789", "--limit", "5",
    )
    assert result.exit_code == 0, result.output
    sent = route.calls.last.request.url.params
    assert sent["asOf"] == "1234567890123456789"
    assert sent["limit"] == "5"
    assert "asOfTag" not in sent


def test_preview_with_iceberg_refuses_as_of_tag(runner, respx_mock):
    _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders/preview",
        json={},
    )
    result = _invoke(
        runner, "preview", "acme", "db1", "main", "orders", "--iceberg", "ice", "--as-of-tag", "v1",
    )
    assert result.exit_code != 0
    assert "not supported with --iceberg" in result.output


def test_preview_without_iceberg_sends_as_of_as_integer(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/schemas/main/tables/orders/preview",
        json={},
    )
    result = _invoke(runner, "preview", "acme", "db1", "main", "orders", "--as-of", "7")
    assert result.exit_code == 0, result.output
    assert route.calls.last.request.url.params["asOf"] == "7"


# ---------------------------------------------------------------------------
# data-diff
# ---------------------------------------------------------------------------


def test_data_diff_with_iceberg_hits_iceberg_path(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders/data-diff",
        json={},
    )
    result = _invoke(
        runner, "data-diff", "acme", "db1", "main", "orders", "--iceberg", "ice",
        "--from", "1", "--to", "2", "--change-type", "added",
    )
    assert result.exit_code == 0, result.output
    sent = route.calls.last.request.url.params
    assert sent["from"] == "1"
    assert sent["to"] == "2"
    assert sent["changeType"] == "added"
    assert "cursor" not in sent


def test_data_diff_with_iceberg_refuses_cursor(runner, respx_mock):
    _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/iceberg/ice/schemas/main/tables/orders/data-diff",
        json={},
    )
    result = _invoke(
        runner, "data-diff", "acme", "db1", "main", "orders", "--iceberg", "ice",
        "--from", "1", "--to", "2", "--cursor", "c1",
    )
    assert result.exit_code != 0
    assert "not supported with --iceberg" in result.output


def test_data_diff_without_iceberg_still_sends_cursor(runner, respx_mock):
    route = _mock(
        respx_mock,
        f"{BASE}/api/catalog/tenant/acme/database/db1/schemas/main/tables/orders/data-diff",
        json={},
    )
    result = _invoke(
        runner, "data-diff", "acme", "db1", "main", "orders",
        "--from", "1", "--to", "2", "--cursor", "c1",
    )
    assert result.exit_code == 0, result.output
    assert route.calls.last.request.url.params["cursor"] == "c1"
