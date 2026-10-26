import json
import os
import stat

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
    return RestClient(Settings(manager_url=BASE, token="k"))


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


def test_provision_spec_holds_the_three_create_requests():
    from qod_cli.serve_provision import provision_spec

    assert provision_spec("default", _target(), "bi", 1) == {
        "tenant": {
            "id": "default", "displayName": "default", "authProvider": "db", "authConfig": {},
        },
        "database": {
            "tenant": "default",
            "name": "sales",
            "kind": "duckdb-file",
            "metastore": {"dbName": "sales", "schemaName": "main"},
            "dataPath": "/abs/sales.duckdb",
            "objectStore": {},
            "initSql": "",
        },
        "pool": {
            "tenant": "default",
            "tenantDb": "default_sales",
            "pool": "bi",
            "size": 1,
            "roleDistribution": {"writeonly": 0, "readonly": 0, "dual": 1},
            "idleTimeoutSec": -1,
            "maxConcurrentPerNode": 0,
            "disabled": False,
            "cpu": "",
            "memory": "",
            "podTemplateYaml": "",
            "startSuspended": False,
            "lockdown": "inherit",
        },
    }


def test_provision_spec_matches_what_attach_mode_posts(client, respx_mock):
    # One shape for both paths: the boot hook gets exactly the bodies REST would.
    from qod_cli.serve_provision import provision_spec

    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    tenant = respx_mock.post(f"{BASE}/api/tenant/create").mock(
        return_value=httpx.Response(200, json={})
    )
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": []})
    )
    database = respx_mock.post(f"{BASE}/api/database/create").mock(
        return_value=httpx.Response(200, json={})
    )
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": []})
    )
    pool = respx_mock.post(f"{BASE}/api/pool/create").mock(
        return_value=httpx.Response(200, json={})
    )
    target = _target(object_store={"s3_region": "eu-west-1"}, init_sql="CREATE VIEW v AS SELECT 1")
    ensure_tenant(client, "default")
    ensure_database(client, "default", target)
    ensure_pool(client, "default", "default_sales", "bi", 2)
    assert provision_spec("default", target, "bi", 2) == {
        "tenant": json.loads(tenant.calls[0].request.content),
        "database": json.loads(database.calls[0].request.content),
        "pool": json.loads(pool.calls[0].request.content),
    }


@pytest.mark.skipif(os.name == "nt", reason="POSIX file modes not applicable on Windows")
def test_write_spec_is_owner_only_and_replaces_an_earlier_file(tmp_path):
    from qod_cli.serve_provision import write_spec

    path = tmp_path / "serve-provision.json"
    path.write_text("left by an earlier boot")
    os.chmod(path, 0o644)
    write_spec(path, {"tenant": {"id": "default"}})
    assert json.loads(path.read_text()) == {"tenant": {"id": "default"}}
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


def test_wait_outcome_returns_once_the_manager_wrote_it(tmp_path):
    from qod_cli.serve_provision import wait_outcome

    path = tmp_path / "serve-provision.outcome.json"
    writes = []

    def manager_writes(_s):
        writes.append(1)
        path.write_text('{"status": "failed", "step": "database", "reason": "x"}')

    outcome = wait_outcome(path, timeout_s=10, interval_s=0, sleep=manager_writes)
    assert outcome == {"status": "failed", "step": "database", "reason": "x"}
    assert writes == [1]


def test_wait_outcome_gives_none_at_the_deadline(tmp_path):
    # A manager release without the boot hook never writes an outcome; the caller says so.
    from qod_cli.serve_provision import wait_outcome

    ticks = iter([0.0, 1.0, 2.0, 99.0])
    outcome = wait_outcome(
        tmp_path / "serve-provision.outcome.json", timeout_s=5, interval_s=0,
        sleep=lambda _s: None, now=lambda: next(ticks),
    )
    assert outcome is None


def test_wait_outcome_refuses_content_that_is_no_outcome(tmp_path):
    from qod_cli.serve_provision import wait_outcome

    path = tmp_path / "serve-provision.outcome.json"
    path.write_text('{"state": "done"}')
    with pytest.raises(ProvisionError, match="no outcome this CLI understands"):
        wait_outcome(path, timeout_s=5, sleep=lambda _s: None)


@pytest.mark.parametrize(
    "step, command",
    [
        ("tenant", "qod tenant create default"),
        (
            "database",
            "qod database create --tenant default --name sales --kind duckdb-file "
            "--data-path <path> --init-sql <sql>",
        ),
        ("pool", "qod pool create --tenant default --db default_sales --pool bi --size 1 --dual 1"),
        ("spec", ""),
    ],
)
def test_finish_by_hand_names_the_rest_paths_command(step, command):
    # The same hand-run command the REST path prints for that step (`<path>` and `<sql>`
    # are part of the database command's text today); none for an unreadable spec file.
    from qod_cli.serve_provision import finish_by_hand

    assert finish_by_hand(step, "default", _target(), "bi", 1) == command


def test_provision_by_hand_lists_the_three_commands_in_order():
    from qod_cli.serve_provision import finish_by_hand, provision_by_hand

    assert provision_by_hand("default", _target(), "bi", 1).splitlines() == [
        finish_by_hand(step, "default", _target(), "bi", 1)
        for step in ("tenant", "database", "pool")
    ]


def test_ensure_pool_refuses_a_pool_name_serving_another_database(client, respx_mock):
    # The edge resolves (tenant, pool) at the handshake, so a pool name is unique per
    # tenant: the fix is another name, never a create that would collide.
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": [
            {"tenant": "default", "tenantDb": "default_other", "pool": "bi"}
        ]})
    )
    create = respx_mock.post(f"{BASE}/api/pool/create")
    with pytest.raises(ProvisionError, match="already serves database 'default_other'") as ei:
        ensure_pool(client, "default", "default_sales", "bi", 1)
    assert ei.value.manual == "serve it under another name: qod serve <target> --pool <other>"
    assert not create.called
