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
