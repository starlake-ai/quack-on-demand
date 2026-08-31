import httpx

from qod_cli.config import load_settings
from qod_cli.main import app

BASE = "http://localhost:20900"


def mock_login_routes(respx_mock, host="edge.example"):
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(
            200, json={"token": "jwt-abc", "username": "admin", "superuser": True}
        )
    )
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(
            200, json={"flightSqlHost": host, "flightSqlPort": 31338, "flightSqlTls": True}
        )
    )


def test_login_stores_token_and_edge_config(runner, respx_mock):
    mock_login_routes(respx_mock)
    result = runner.invoke(app, ["login", "--username", "admin"], input="secret\n")
    assert result.exit_code == 0
    st = load_settings()
    assert st.token == "jwt-abc"
    assert st.sql_user == "admin"
    assert st.edge_host == "edge.example"
    assert st.edge_port == 31338
    assert st.edge_tls is True


def test_login_substitutes_wildcard_edge_host(runner, respx_mock):
    mock_login_routes(respx_mock, host="0.0.0.0")
    runner.invoke(app, ["login", "--username", "admin"], input="secret\n")
    assert load_settings().edge_host == "localhost"


def test_login_stores_tenant_and_pool(runner, respx_mock):
    mock_login_routes(respx_mock)
    result = runner.invoke(
        app,
        ["login", "--username", "bob", "--tenant", "acme", "--pool", "bi"],
        input="pw\n",
    )
    assert result.exit_code == 0
    st = load_settings()
    assert st.tenant == "acme"
    assert st.pool == "bi"


def test_login_without_tenant_pool_leaves_profile_values(runner, respx_mock):
    mock_login_routes(respx_mock)
    runner.invoke(
        app, ["login", "--username", "bob", "--tenant", "acme", "--pool", "bi"], input="pw\n"
    )
    # A later login omitting the flags must not clear the stored values
    # (save_profile drops None entries).
    runner.invoke(app, ["login", "--username", "bob"], input="pw\n")
    st = load_settings()
    assert st.tenant == "acme"
    assert st.pool == "bi"


def test_login_sends_credentials(runner, respx_mock):
    mock_login_routes(respx_mock)
    runner.invoke(app, ["login", "--username", "bob", "--tenant", "acme"], input="pw\n")
    import json

    sent = json.loads(respx_mock.calls[0].request.content)
    assert sent == {"username": "bob", "password": "pw", "tenant": "acme"}


def test_logout(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/logout").mock(return_value=httpx.Response(200))
    assert runner.invoke(app, ["logout"]).exit_code == 0
    assert route.called


def test_whoami(runner, respx_mock):
    respx_mock.get(f"{BASE}/api/auth/whoami").mock(
        return_value=httpx.Response(200, json={"username": "admin", "role": "admin"})
    )
    result = runner.invoke(app, ["whoami"])
    assert result.exit_code == 0 and "admin" in result.output


def test_auth_mode(runner, respx_mock):
    route = respx_mock.get(f"{BASE}/api/auth/mode").mock(
        return_value=httpx.Response(200, json={"mode": "db"})
    )
    assert runner.invoke(app, ["auth", "mode"]).exit_code == 0
    assert route.called
    assert dict(route.calls.last.request.url.params) == {}


def test_auth_mode_with_tenant(runner, respx_mock):
    route = respx_mock.get(f"{BASE}/api/auth/mode").mock(
        return_value=httpx.Response(200, json={"mode": "oidc"})
    )
    result = runner.invoke(app, ["auth", "mode", "--tenant", "acme"])
    assert result.exit_code == 0
    assert route.called
    assert dict(route.calls.last.request.url.params) == {"tenant": "acme"}


def test_change_password_sends_credentials(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/change-password").mock(
        return_value=httpx.Response(200)
    )
    result = runner.invoke(
        app,
        ["auth", "change-password", "--username", "bob", "--tenant", "acme"],
        input="oldpw\nnewpw\nnewpw\n",
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {
        "tenant": "acme",
        "username": "bob",
        "currentPassword": "oldpw",
        "newPassword": "newpw",
    }


def test_change_password_without_tenant(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/change-password").mock(
        return_value=httpx.Response(200)
    )
    result = runner.invoke(
        app,
        ["auth", "change-password", "--username", "root"],
        input="oldpw\nnewpw\nnewpw\n",
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {
        "tenant": None,
        "username": "root",
        "currentPassword": "oldpw",
        "newPassword": "newpw",
    }


def test_forgot_password_sends_credentials(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/forgot-password").mock(
        return_value=httpx.Response(200)
    )
    result = runner.invoke(
        app, ["auth", "forgot-password", "--username", "bob", "--tenant", "acme"]
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"tenant": "acme", "username": "bob"}


def test_forgot_password_without_tenant(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/forgot-password").mock(
        return_value=httpx.Response(200)
    )
    result = runner.invoke(app, ["auth", "forgot-password", "--username", "root"])
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"tenant": None, "username": "root"}


def test_reset_password_prompts_and_sends_token(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/reset-password").mock(
        return_value=httpx.Response(200)
    )
    result = runner.invoke(
        app, ["auth", "reset-password"], input="tok-123\nnewpw\nnewpw\n"
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"token": "tok-123", "newPassword": "newpw"}


def test_pat_create_sends_name_and_prints_token(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/pat/create").mock(
        return_value=httpx.Response(
            200,
            json={"id": "pat-1", "name": "claude", "token": "qod_pat_abc123", "expiresAt": None},
        )
    )
    result = runner.invoke(app, ["auth", "pat", "create", "--name", "claude"])
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"name": "claude", "expiresAt": None, "dropAdmin": False}
    assert "qod_pat_abc123" in result.output


def test_pat_create_sends_expiry(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/pat/create").mock(
        return_value=httpx.Response(
            200,
            json={
                "id": "pat-2",
                "name": "ci",
                "token": "qod_pat_xyz",
                "expiresAt": "2027-01-01T00:00:00Z",
            },
        )
    )
    result = runner.invoke(
        app,
        ["auth", "pat", "create", "--name", "ci", "--expires-at", "2027-01-01T00:00:00Z"],
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"name": "ci", "expiresAt": "2027-01-01T00:00:00Z", "dropAdmin": False}


def _mock_pat_create(respx_mock):
    return respx_mock.post(f"{BASE}/api/auth/pat/create").mock(
        return_value=httpx.Response(
            200,
            json={"id": "pat-3", "name": "agent", "token": "qod_pat_scoped", "expiresAt": None},
        )
    )


def test_pat_create_omits_unset_scope_fields(runner, respx_mock):
    """Absent scope flags must be omitted from the payload, not sent as empty lists or None,
    since the server reads an absent key as unrestricted and an empty list as nothing."""
    route = _mock_pat_create(respx_mock)
    result = runner.invoke(app, ["auth", "pat", "create", "--name", "agent"])
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    for key in ("roles", "databases", "pools", "tools", "verbCeiling", "stmtTimeoutMs", "maxRows"):
        assert key not in sent, f"{key} should be omitted when its flag is not passed"
    assert sent["dropAdmin"] is False


def test_pat_create_scope_flags_serialize_to_lists(runner, respx_mock):
    route = _mock_pat_create(respx_mock)
    result = runner.invoke(
        app,
        [
            "auth",
            "pat",
            "create",
            "--name",
            "agent",
            "--database",
            "sales",
            "--database",
            "hr",
            "--role",
            "analyst",
            "--pool",
            "bi",
            "--tool",
            "read_query",
        ],
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent["databases"] == ["sales", "hr"]
    assert sent["roles"] == ["analyst"]
    assert sent["pools"] == ["bi"]
    assert sent["tools"] == ["read_query"]


def test_pat_create_sends_scalar_scope_fields(runner, respx_mock):
    route = _mock_pat_create(respx_mock)
    result = runner.invoke(
        app,
        [
            "auth",
            "pat",
            "create",
            "--name",
            "agent",
            "--drop-admin",
            "--stmt-timeout-ms",
            "5000",
            "--max-rows",
            "1000",
        ],
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent["dropAdmin"] is True
    assert sent["stmtTimeoutMs"] == 5000
    assert sent["maxRows"] == 1000


def test_pat_create_rejects_invalid_verb_ceiling(runner, respx_mock):
    route = _mock_pat_create(respx_mock)
    result = runner.invoke(
        app, ["auth", "pat", "create", "--name", "agent", "--verb-ceiling", "bogus"]
    )
    assert result.exit_code != 0
    assert not route.called


def test_pat_create_verb_ceiling_is_uppercased(runner, respx_mock):
    route = _mock_pat_create(respx_mock)
    result = runner.invoke(
        app, ["auth", "pat", "create", "--name", "agent", "--verb-ceiling", "ro"]
    )
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent["verbCeiling"] == "RO"


def test_pat_list_hits_the_list_route(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/pat/list").mock(
        return_value=httpx.Response(200, json={"tokens": []})
    )
    result = runner.invoke(app, ["auth", "pat", "list"])
    assert result.exit_code == 0
    assert route.called


def test_pat_revoke_sends_id(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/auth/pat/revoke").mock(
        return_value=httpx.Response(200, json={})
    )
    result = runner.invoke(app, ["auth", "pat", "revoke", "--id", "pat-1"])
    assert result.exit_code == 0
    assert route.called
    import json

    sent = json.loads(route.calls.last.request.content)
    assert sent == {"id": "pat-1"}
