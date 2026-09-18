import httpx
import pytest

from qod_cli.config import Settings
from qod_cli.rest import RestClient
from qod_cli.serve_provision import (
    ProvisionError,
    ensure_database,
    ensure_pool,
    ensure_tenant,
    wait_node_routable,
    wait_ready,
)
from qod_cli.serve_target import ServeTarget

BASE = "http://localhost:20900"


@pytest.fixture
def client():
    return RestClient(Settings(manager_url=BASE, api_key="k"))


def _target(**kw):
    base = dict(kind="duckdb-file", name="sales", data_path="/abs/sales.duckdb",
                metastore={"dbName": "sales", "schemaName": "main"})
    base.update(kw)
    return ServeTarget(**base)


def test_wait_ready_returns_once_ready_is_200(client, respx_mock):
    route = respx_mock.get(f"{BASE}/ready")
    route.side_effect = [httpx.Response(503), httpx.Response(200, json={})]
    wait_ready(client, timeout_s=10, interval_s=0, sleep=lambda _s: None)
    assert route.call_count == 2


def test_wait_ready_raises_on_timeout(client, respx_mock):
    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(503))
    ticks = iter([0.0, 1.0, 2.0, 99.0])
    with pytest.raises(ProvisionError, match="never went green"):
        wait_ready(client, timeout_s=5, interval_s=0, sleep=lambda _s: None,
                   now=lambda: next(ticks))


def test_ensure_tenant_creates_when_absent(client, respx_mock):
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    create = respx_mock.post(f"{BASE}/api/tenant/create").mock(
        return_value=httpx.Response(200, json={"name": "default"})
    )
    assert ensure_tenant(client, "default") is True
    assert create.called


def test_ensure_tenant_is_a_noop_when_present(client, respx_mock):
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": [{"name": "default", "id": "t-1"}]})
    )
    create = respx_mock.post(f"{BASE}/api/tenant/create")
    assert ensure_tenant(client, "default") is False
    assert not create.called


def test_ensure_tenant_matches_case_insensitively_against_the_normalized_form(client, respx_mock):
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": [{"name": "default", "id": "t-1"}]})
    )
    create = respx_mock.post(f"{BASE}/api/tenant/create")
    assert ensure_tenant(client, "Default") is False
    assert not create.called


def test_ensure_database_creates_with_the_suffix_name(client, respx_mock):
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": []})
    )
    create = respx_mock.post(f"{BASE}/api/database/create").mock(
        return_value=httpx.Response(200, json={"name": "default_sales"})
    )
    assert ensure_database(client, "default", _target()) is True
    body = create.calls[0].request.content.decode()
    assert '"name": "sales"' in body or '"name":"sales"' in body


def test_ensure_database_reuses_a_matching_row(client, respx_mock):
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": [
            {"name": "default_sales", "kind": "duckdb-file", "dataPath": "/abs/sales.duckdb"}
        ]})
    )
    create = respx_mock.post(f"{BASE}/api/database/create")
    assert ensure_database(client, "default", _target()) is False
    assert not create.called


def test_ensure_database_refuses_a_name_collision_on_different_data(client, respx_mock):
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": [
            {"name": "default_sales", "kind": "duckdb-file", "dataPath": "/other/sales.duckdb"}
        ]})
    )
    with pytest.raises(ProvisionError, match="--name") as ei:
        ensure_database(client, "default", _target())
    assert "--name" in ei.value.manual


def test_ensure_database_refuses_a_memory_target_reusing_a_different_directory(client, respx_mock):
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": [
            {"name": "default_reports", "kind": "memory", "dataPath": "", "initSql": "<viewsA>"}
        ]})
    )
    target = _target(kind="memory", name="reports", data_path="", init_sql="<viewsB>",
                      metastore={})
    with pytest.raises(ProvisionError, match="--name") as ei:
        ensure_database(client, "default", target)
    assert "--name" in ei.value.manual


def test_ensure_database_reuses_a_matching_memory_row(client, respx_mock):
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": [
            {"name": "default_reports", "kind": "memory", "dataPath": "", "initSql": "<viewsA>"}
        ]})
    )
    create = respx_mock.post(f"{BASE}/api/database/create")
    target = _target(kind="memory", name="reports", data_path="", init_sql="<viewsA>",
                      metastore={})
    assert ensure_database(client, "default", target) is False
    assert not create.called


def test_ensure_pool_creates_one_dual_node(client, respx_mock):
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": []})
    )
    create = respx_mock.post(f"{BASE}/api/pool/create").mock(
        return_value=httpx.Response(200, json={"pool": "bi"})
    )
    assert ensure_pool(client, "default", "default_sales", "bi", 1) is True
    body = create.calls[0].request.content.decode().replace(" ", "")
    assert '"tenantDb":"default_sales"' in body
    assert '"dual":1' in body


def test_ensure_pool_is_a_noop_when_present(client, respx_mock):
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": [
            {"tenant": "default", "tenantDb": "default_sales", "pool": "bi"}
        ]})
    )
    create = respx_mock.post(f"{BASE}/api/pool/create")
    assert ensure_pool(client, "default", "default_sales", "bi", 1) is False
    assert not create.called


def _pool_list_response(healthy: bool, tenant="default", tenant_db="default_sales", pool="bi"):
    return httpx.Response(
        200,
        json={
            "pools": [
                {
                    "tenant": tenant,
                    "tenantDb": tenant_db,
                    "pool": pool,
                    "nodes": [{"healthy": healthy}],
                }
            ]
        },
    )


def test_wait_node_routable_true_once_a_node_is_healthy(client, respx_mock):
    route = respx_mock.get(f"{BASE}/api/pool/list")
    route.side_effect = [_pool_list_response(False), _pool_list_response(True)]
    ticks = iter([0.0, 1.0, 2.0, 3.0])
    result = wait_node_routable(
        client, "default", "default_sales", "bi", timeout_s=10, interval_s=0,
        sleep=lambda _s: None, now=lambda: next(ticks),
    )
    assert result is True
    assert route.call_count == 2


def test_wait_node_routable_false_at_deadline_when_never_healthy(client, respx_mock):
    respx_mock.get(f"{BASE}/api/pool/list").mock(return_value=_pool_list_response(False))
    ticks = iter([0.0, 1.0, 2.0, 99.0])
    result = wait_node_routable(
        client, "default", "default_sales", "bi", timeout_s=5, interval_s=0,
        sleep=lambda _s: None, now=lambda: next(ticks),
    )
    assert result is False


def test_wait_node_routable_keeps_polling_through_an_api_error(client, respx_mock):
    route = respx_mock.get(f"{BASE}/api/pool/list")
    route.side_effect = [httpx.Response(500), _pool_list_response(True)]
    ticks = iter([0.0, 1.0, 2.0, 3.0])
    result = wait_node_routable(
        client, "default", "default_sales", "bi", timeout_s=10, interval_s=0,
        sleep=lambda _s: None, now=lambda: next(ticks),
    )
    assert result is True
    assert route.call_count == 2
