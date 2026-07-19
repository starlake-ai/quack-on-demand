"""Table-driven happy-path tests: one row per command asserts method, path,
query params, and JSON body. Commands with real logic (login, manifest file
I/O, sql) have dedicated test modules instead."""

import json

import httpx
import pytest
import respx

from qod_cli.main import app

BASE = "http://localhost:20900"

# (argv, method, path, expected_params, expected_body)
CASES = [
    (["tenant", "list"], "GET", "/api/tenant/list", {}, None),
    (
        ["tenant", "create", "acme", "--display-name", "Acme Corp"],
        "POST",
        "/api/tenant/create",
        {},
        {"id": "acme", "displayName": "Acme Corp", "authProvider": "db", "authConfig": {}},
    ),
    (
        ["tenant", "create", "okta1", "--auth-provider", "keycloak", "--auth-config", "issuer=https://kc", "--auth-config", "realm=r1"],
        "POST",
        "/api/tenant/create",
        {},
        {"id": "okta1", "displayName": "", "authProvider": "keycloak", "authConfig": {"issuer": "https://kc", "realm": "r1"}},
    ),
    (["tenant", "delete", "acme"], "POST", "/api/tenant/delete", {}, {"name": "acme"}),
    (
        ["tenant", "set-disabled", "acme", "--disabled"],
        "POST",
        "/api/tenant/setDisabled",
        {},
        {"name": "acme", "disabled": True},
    ),
    (
        ["tenant", "set-auth", "acme", "--auth-provider", "google", "--auth-config", "hd=acme.com"],
        "POST",
        "/api/tenant/setAuth",
        {},
        {"name": "acme", "authProvider": "google", "authConfig": {"hd": "acme.com"}},
    ),
    (["database", "list", "--tenant", "acme"], "GET", "/api/database/list", {"tenant": "acme"}, None),
    (
        ["database", "create", "--tenant", "acme", "--name", "tpch1", "--metastore", "pgHost=pg", "--data-path", "/data"],
        "POST",
        "/api/database/create",
        {},
        {
            "tenant": "acme",
            "name": "tpch1",
            "kind": "ducklake",
            "metastore": {"pgHost": "pg"},
            "dataPath": "/data",
            "objectStore": {},
            "initSql": "",
        },
    ),
    (
        ["database", "update", "--tenant", "acme", "--name", "tpch1", "--default-schema", "main"],
        "POST",
        "/api/database/update",
        {},
        {"tenant": "acme", "name": "tpch1", "defaultSchema": "main"},
    ),
    (
        ["database", "delete", "--tenant", "acme", "--name", "tpch1"],
        "POST",
        "/api/database/delete",
        {},
        {"tenant": "acme", "name": "tpch1"},
    ),
    (["pool", "list"], "GET", "/api/pool/list", {}, None),
    (
        ["pool", "create", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--size", "2", "--writeonly", "1", "--readonly", "1", "--dual", "0"],
        "POST",
        "/api/pool/create",
        {},
        {
            "tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "size": 2,
            "roleDistribution": {"writeonly": 1, "readonly": 1, "dual": 0},
            "idleTimeoutSec": -1, "maxConcurrentPerNode": 0, "disabled": False,
            "cpu": "", "memory": "", "podTemplateYaml": "", "startSuspended": False,
        },
    ),
    (
        [
            "pool", "create", "--tenant", "acme", "--db", "tpch1", "--pool", "bi",
            "--size", "2", "--writeonly", "1", "--readonly", "1", "--dual", "0",
            "--start-suspended", "--cohort", "1,1,0",
        ],
        "POST",
        "/api/pool/create",
        {},
        {
            "tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "size": 2,
            "roleDistribution": {"writeonly": 1, "readonly": 1, "dual": 0},
            "idleTimeoutSec": -1, "maxConcurrentPerNode": 0, "disabled": False,
            "cpu": "", "memory": "", "podTemplateYaml": "", "startSuspended": True,
            "cohorts": [
                {"placement": {"nodeSelector": {}, "tolerations": []}, "distribution": {"writeonly": 1, "readonly": 1, "dual": 0}},
            ],
        },
    ),
    (
        ["pool", "suspend", "--tenant", "acme", "--db", "tpch1", "--pool", "bi"],
        "POST", "/api/pool/suspend", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi"},
    ),
    (
        ["pool", "resume", "--tenant", "acme", "--db", "tpch1", "--pool", "bi"],
        "POST", "/api/pool/resume", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi"},
    ),
    (
        ["pool", "status", "--tenant", "acme", "--db", "tpch1", "--pool", "bi"],
        "GET", "/api/pool/acme/tpch1/bi/status", {}, None,
    ),
    (
        ["pool", "scale", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--target-size", "3", "--writeonly", "1", "--readonly", "2", "--dual", "0", "--force"],
        "POST",
        "/api/pool/scale",
        {},
        {
            "tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "targetSize": 3,
            "roleDistribution": {"writeonly": 1, "readonly": 2, "dual": 0}, "force": True,
        },
    ),
    (
        ["pool", "stop", "--tenant", "acme", "--db", "tpch1", "--pool", "bi"],
        "POST", "/api/pool/stop", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "force": False},
    ),
    (
        ["pool", "delete", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--force"],
        "POST", "/api/pool/delete", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "force": True},
    ),
    (
        ["pool", "set-disabled", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--disabled"],
        "POST", "/api/pool/setDisabled", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "disabled": True},
    ),
    (
        ["pool", "set-resources", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--cpu", "2", "--memory", "4Gi"],
        "POST", "/api/pool/setResources", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "cpu": "2", "memory": "4Gi"},
    ),
    (
        ["pool", "permission", "list", "--tenant", "acme"],
        "GET", "/api/pool/permission/list", {"tenant": "acme"}, None,
    ),
    (
        ["pool", "permission", "grant", "--tenant", "acme", "--user-id", "u1", "--pool-id", "p1"],
        "POST", "/api/pool/permission/grant", {},
        {"tenant": "acme", "poolId": "p1", "userId": "u1"},
    ),
    (
        ["pool", "permission", "grant", "--tenant", "acme", "--group-id", "g1"],
        "POST", "/api/pool/permission/grant", {},
        {"tenant": "acme", "groupId": "g1"},
    ),
    (["pool", "permission", "revoke", "pp1"], "POST", "/api/pool/permission/revoke", {}, {"id": "pp1"}),
    (
        ["node", "quarantine", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--node-id", "n1"],
        "POST", "/api/node/quarantine", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "nodeId": "n1"},
    ),
    (
        ["node", "unquarantine", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--node-id", "n1"],
        "POST", "/api/node/unquarantine", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "nodeId": "n1"},
    ),
    (
        ["node", "restart", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--node-id", "n1"],
        "POST", "/api/node/restart", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "nodeId": "n1"},
    ),
    (
        ["node", "set-max-concurrent", "--tenant", "acme", "--db", "tpch1", "--pool", "bi", "--node-id", "n1", "--max", "8"],
        "POST", "/api/node/setMaxConcurrent", {},
        {"tenant": "acme", "tenantDb": "tpch1", "pool": "bi", "nodeId": "n1", "max": 8},
    ),
    (["node", "statements", "--limit", "20"], "GET", "/api/node/statements", {"limit": "20"}, None),
    (["node", "active-statements"], "GET", "/api/node/active-statements", {}, None),
    (["statement", "kill", "s1"], "POST", "/api/statement/kill", {}, {"id": "s1"}),
    (["user", "list"], "GET", "/api/user/list", {}, None),
    (["user", "list", "--tenant", "acme"], "GET", "/api/user/list", {"tenant": "acme"}, None),
    (
        ["user", "create", "--tenant", "acme", "--username", "bob", "--password", "pw", "--role", "admin"],
        "POST", "/api/user/create", {},
        {"tenant": "acme", "username": "bob", "password": "pw", "role": "admin"},
    ),
    (
        ["user", "create", "--superuser", "--username", "root", "--password", "pw"],
        "POST", "/api/user/create", {},
        {"tenant": None, "username": "root", "password": "pw", "role": "user"},
    ),
    (
        ["user", "update", "u1", "--role", "admin"],
        "POST", "/api/user/update", {}, {"id": "u1", "role": "admin"},
    ),
    (["user", "delete", "u1"], "POST", "/api/user/delete", {}, {"id": "u1"}),
    (["user", "effective", "u1"], "GET", "/api/user/u1/effective", {}, None),
    (["role", "list", "--tenant", "acme"], "GET", "/api/role/list", {"tenant": "acme"}, None),
    (
        ["role", "create", "--tenant", "acme", "--name", "analyst", "--description", "read-only"],
        "POST", "/api/role/create", {},
        {"tenant": "acme", "name": "analyst", "description": "read-only"},
    ),
    (["role", "delete", "r1"], "POST", "/api/role/delete", {}, {"id": "r1"}),
    (
        ["role", "permission", "list", "--role-id", "r1"],
        "GET", "/api/role/permission/list", {"roleId": "r1"}, None,
    ),
    (
        ["role", "permission", "grant", "--role-id", "r1", "--verb", "RO", "--schema", "main", "--table", "orders"],
        "POST", "/api/role/permission/grant", {},
        {"roleId": "r1", "catalog": "*", "schema": "main", "table": "orders", "verb": "RO"},
    ),
    (["role", "permission", "revoke", "rp1"], "POST", "/api/role/permission/revoke", {}, {"id": "rp1"}),
    (
        ["role", "row-policy", "list", "--role-id", "r1"],
        "GET", "/api/role/row-policy/list", {"roleId": "r1"}, None,
    ),
    (
        ["role", "row-policy", "create", "--role-id", "r1", "--catalog", "c", "--schema", "s", "--table", "t", "--predicate-sql", "region = 'EU'"],
        "POST", "/api/role/row-policy/create", {},
        {"roleId": "r1", "catalogName": "c", "schemaName": "s", "tableName": "t", "predicateSql": "region = 'EU'"},
    ),
    (
        ["role", "row-policy", "update", "rw1", "--predicate-sql", "1=1"],
        "POST", "/api/role/row-policy/update", {}, {"id": "rw1", "predicateSql": "1=1"},
    ),
    (["role", "row-policy", "delete", "rw1"], "POST", "/api/role/row-policy/delete", {}, {"id": "rw1"}),
    (
        ["role", "column-policy", "list", "--role-id", "r1"],
        "GET", "/api/role/column-policy/list", {"roleId": "r1"}, None,
    ),
    (
        ["role", "column-policy", "create", "--role-id", "r1", "--catalog", "c", "--schema", "s", "--table", "t", "--column", "email", "--action", "mask"],
        "POST", "/api/role/column-policy/create", {},
        {"roleId": "r1", "catalogName": "c", "schemaName": "s", "tableName": "t", "columnName": "email", "action": "mask"},
    ),
    (
        ["role", "column-policy", "update", "cp1", "--action", "transform", "--transform-sql", "left(email, 3)"],
        "POST", "/api/role/column-policy/update", {},
        {"id": "cp1", "action": "transform", "transformSql": "left(email, 3)"},
    ),
    (["role", "column-policy", "delete", "cp1"], "POST", "/api/role/column-policy/delete", {}, {"id": "cp1"}),
    (["group", "list", "--tenant", "acme"], "GET", "/api/group/list", {"tenant": "acme"}, None),
    (
        ["group", "create", "--tenant", "acme", "--name", "analysts"],
        "POST", "/api/group/create", {}, {"tenant": "acme", "name": "analysts"},
    ),
    (["group", "delete", "g1"], "POST", "/api/group/delete", {}, {"id": "g1"}),
    (
        ["membership", "user-role", "add", "--user-id", "u1", "--role-id", "r1"],
        "POST", "/api/membership/user-role/add", {}, {"userId": "u1", "roleId": "r1"},
    ),
    (
        ["membership", "user-role", "remove", "--user-id", "u1", "--role-id", "r1"],
        "POST", "/api/membership/user-role/remove", {}, {"userId": "u1", "roleId": "r1"},
    ),
    (
        ["membership", "user-group", "add", "--user-id", "u1", "--group-id", "g1"],
        "POST", "/api/membership/user-group/add", {}, {"userId": "u1", "groupId": "g1"},
    ),
    (
        ["membership", "user-group", "remove", "--user-id", "u1", "--group-id", "g1"],
        "POST", "/api/membership/user-group/remove", {}, {"userId": "u1", "groupId": "g1"},
    ),
    (
        ["membership", "group-role", "add", "--group-id", "g1", "--role-id", "r1"],
        "POST", "/api/membership/group-role/add", {}, {"groupId": "g1", "roleId": "r1"},
    ),
    (
        ["membership", "group-role", "remove", "--group-id", "g1", "--role-id", "r1"],
        "POST", "/api/membership/group-role/remove", {}, {"groupId": "g1", "roleId": "r1"},
    ),
    (
        ["membership", "group-role", "list", "--group-id", "g1"],
        "GET", "/api/membership/group-role/list", {"groupId": "g1"}, None,
    ),
    (
        ["catalog", "schemas", "acme", "tpch1"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas", {}, None,
    ),
    (
        ["catalog", "tables", "acme", "tpch1", "main"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas/main/tables", {}, None,
    ),
    (
        ["catalog", "describe", "acme", "tpch1", "main", "orders", "--as-of", "42"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas/main/tables/orders",
        {"asOf": "42"}, None,
    ),
    (
        ["catalog", "snapshots", "acme", "tpch1", "--limit", "5", "--table", "main.orders"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/snapshots",
        {"limit": "5", "table": "main.orders"}, None,
    ),
    (
        ["catalog", "history", "acme", "tpch1", "main", "orders", "--limit", "10", "--operation", "DELETE"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas/main/tables/orders/history",
        {"limit": "10", "operation": "DELETE"}, None,
    ),
    (
        ["catalog", "preview", "acme", "tpch1", "main", "orders", "--as-of-tag", "v1", "--limit", "50"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas/main/tables/orders/preview",
        {"asOfTag": "v1", "limit": "50"}, None,
    ),
    (
        ["catalog", "data-diff", "acme", "tpch1", "main", "orders", "--from", "10", "--to", "20", "--change-type", "insert"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas/main/tables/orders/data-diff",
        {"from": "10", "to": "20", "changeType": "insert"}, None,
    ),
    (
        ["catalog", "schema-diff", "acme", "tpch1", "main", "orders", "--from", "10", "--to", "20"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/schemas/main/tables/orders/schema-diff",
        {"from": "10", "to": "20"}, None,
    ),
    (
        ["catalog", "recoverable", "acme", "tpch1", "--limit", "10"],
        "GET", "/api/catalog/tenant/acme/database/tpch1/recoverable", {"limit": "10"}, None,
    ),
    (
        ["catalog", "undrop", "--tenant", "acme", "--db", "tpch1", "--schema", "main", "--table", "orders", "--as-name", "orders_restored"],
        "POST", "/api/catalog/undrop", {},
        {"tenant": "acme", "tenantDb": "tpch1", "schema": "main", "table": "orders", "asName": "orders_restored"},
    ),
    (
        ["tag", "create", "--tenant", "acme", "--db", "tpch1", "--name", "v1", "--snapshot-id", "42", "--protected"],
        "POST", "/api/catalog/tag/create", {},
        {"tenant": "acme", "tenantDb": "tpch1", "name": "v1", "snapshotId": 42, "isProtected": True},
    ),
    (
        ["tag", "delete", "--tenant", "acme", "--db", "tpch1", "--name", "v1"],
        "POST", "/api/catalog/tag/delete", {},
        {"tenant": "acme", "tenantDb": "tpch1", "name": "v1"},
    ),
    (
        ["tag", "protect", "--tenant", "acme", "--db", "tpch1", "--name", "v1", "--unprotected"],
        "POST", "/api/catalog/tag/protect", {},
        {"tenant": "acme", "tenantDb": "tpch1", "name": "v1", "isProtected": False},
    ),
    (
        ["maintenance", "policy", "--tenant", "acme", "--db", "tpch1"],
        "GET", "/api/maintenance/policy", {"tenant": "acme", "tenantDb": "tpch1"}, None,
    ),
    (
        ["maintenance", "policy-upsert", "--tenant", "acme", "--db", "tpch1", "--scope-kind", "table", "--scope-schema", "main", "--scope-table", "orders", "--enabled", "--retention-days", "7"],
        "POST", "/api/maintenance/policy/upsert", {},
        {
            "tenant": "acme", "tenantDb": "tpch1", "scopeKind": "table",
            "scopeSchema": "main", "scopeTable": "orders", "enabled": True, "retentionDays": 7,
        },
    ),
    (["maintenance", "policy-delete", "mp1"], "POST", "/api/maintenance/policy/delete", {}, {"id": "mp1"}),
    (
        ["maintenance", "run", "--tenant", "acme", "--db", "tpch1", "--operations", "compact,cleanup"],
        "POST", "/api/maintenance/run", {},
        {"tenant": "acme", "tenantDb": "tpch1", "operations": "compact,cleanup"},
    ),
    (
        ["maintenance", "runs", "--tenant", "acme", "--db", "tpch1", "--limit", "5"],
        "GET", "/api/maintenance/runs", {"tenant": "acme", "tenantDb": "tpch1", "limit": "5"}, None,
    ),
    (
        ["federation", "list", "acme", "tpch1"],
        "GET", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources", {}, None,
    ),
    (
        ["federation", "get", "acme", "tpch1", "pgsrc"],
        "GET", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources/pgsrc", {}, None,
    ),
    (
        ["federation", "create", "acme", "tpch1", "--alias", "pgsrc", "--setup-sql", "ATTACH ..."],
        "POST", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources", {},
        {"alias": "pgsrc", "setupSql": "ATTACH ...", "disabled": False},
    ),
    (
        ["federation", "delete", "acme", "tpch1", "pgsrc"],
        "DELETE", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources/pgsrc", {}, None,
    ),
    (
        ["federation", "secret", "list", "acme", "tpch1", "pgsrc"],
        "GET", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources/pgsrc/secrets", {}, None,
    ),
    (
        ["federation", "secret", "set", "acme", "tpch1", "pgsrc", "--name", "pgpass", "--value", "s3cr3t"],
        "PUT", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources/pgsrc/secrets", {},
        {"name": "pgpass", "value": "s3cr3t"},
    ),
    (
        ["federation", "secret", "set", "acme", "tpch1", "pgsrc", "--name", "pgpass", "--external-ref", "env:PGPASS"],
        "PUT", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources/pgsrc/secrets", {},
        {"name": "pgpass", "externalRef": "env:PGPASS"},
    ),
    (
        ["federation", "secret", "delete", "acme", "tpch1", "pgsrc", "pgpass"],
        "DELETE", "/api/tenants/acme/tenant-dbs/tpch1/federated-sources/pgsrc/secrets/pgpass", {}, None,
    ),
    (
        ["audit", "list", "--tenant", "acme", "--action", "login", "--limit", "10", "--no-tenant"],
        "GET", "/api/audit/list",
        {"tenant": "acme", "action": "login", "limit": "10", "noTenant": "true"}, None,
    ),
    (["audit", "actions"], "GET", "/api/audit/actions", {}, None),
    (
        ["usage", "--from", "2026-07-01", "--to", "2026-07-14", "--group-by", "tenant"],
        "GET", "/api/usage",
        {"from": "2026-07-01", "to": "2026-07-14", "groupBy": "tenant"}, None,
    ),
    (
        ["history", "statements", "--tenant", "acme", "--status", "error", "--limit", "5"],
        "GET", "/api/history/statements",
        {"tenant": "acme", "status": "error", "limit": "5"}, None,
    ),
    (
        ["history", "trends", "--granularity", "hour", "--pool", "bi"],
        "GET", "/api/history/trends", {"granularity": "hour", "pool": "bi"}, None,
    ),
]


@pytest.mark.parametrize("argv,method,path,expected_params,expected_body", CASES, ids=lambda v: " ".join(v) if isinstance(v, list) else None)
@respx.mock
def test_command(runner, argv, method, path, expected_params, expected_body):
    route = respx.route(method=method, url=f"{BASE}{path}").mock(
        return_value=httpx.Response(200, json={"ok": True})
    )
    result = runner.invoke(app, ["--json"] + argv)
    assert result.exit_code == 0, result.output
    assert route.called
    request = route.calls.last.request
    assert dict(request.url.params) == expected_params
    if expected_body is None:
        assert request.content == b""
    else:
        assert json.loads(request.content) == expected_body
