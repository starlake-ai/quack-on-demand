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
