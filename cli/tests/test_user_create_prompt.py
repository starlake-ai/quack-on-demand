import json

import httpx

from qod_cli.main import app

BASE = "http://localhost:20900"


def test_user_create_prompts_for_password(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/create").mock(
        return_value=httpx.Response(200, json={"id": "u1"})
    )
    result = runner.invoke(
        app,
        ["user", "create", "--tenant", "acme", "--username", "bob"],
        input="pw1\npw1\n",
    )
    assert result.exit_code == 0, result.output
    assert json.loads(route.calls.last.request.content)["password"] == "pw1"


def test_user_create_superuser_and_tenant_conflict(runner):
    result = runner.invoke(
        app, ["user", "create", "--superuser", "--tenant", "acme", "--username", "x", "--password", "p"]
    )
    assert result.exit_code == 2
