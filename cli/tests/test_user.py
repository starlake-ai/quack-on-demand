import httpx

from qod_cli.main import app

BASE = "http://localhost:20900"


def test_user_update_lock_sends_enabled_false(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/update").mock(
        return_value=httpx.Response(200, json={"id": "u-1", "username": "bob", "enabled": False})
    )
    result = runner.invoke(app, ["user", "update", "u-1", "--no-enabled"])
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"id": "u-1", "enabled": False}


def test_user_update_unlock_sends_enabled_true(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/update").mock(
        return_value=httpx.Response(200, json={"id": "u-1", "username": "bob", "enabled": True})
    )
    result = runner.invoke(app, ["user", "update", "u-1", "--enabled"])
    assert result.exit_code == 0
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"id": "u-1", "enabled": True}


def test_user_update_omits_enabled_by_default(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/update").mock(
        return_value=httpx.Response(200, json={"id": "u-1", "username": "bob", "enabled": True})
    )
    result = runner.invoke(app, ["user", "update", "u-1", "--role", "user"])
    assert result.exit_code == 0
    import json

    sent = json.loads(route.calls.last.request.content)
    assert "enabled" not in sent
