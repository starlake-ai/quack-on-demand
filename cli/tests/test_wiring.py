import httpx
import respx

from qod_cli.main import app

BASE = "http://localhost:20900"


@respx.mock
def test_health(runner):
    respx.get(f"{BASE}/health").mock(
        return_value=httpx.Response(200, json={"status": "ok", "pools": 1})
    )
    result = runner.invoke(app, ["health"])
    assert result.exit_code == 0
    assert "ok" in result.output


@respx.mock
def test_ready(runner):
    respx.get(f"{BASE}/ready").mock(
        return_value=httpx.Response(200, json={"status": "ok", "pools": 0})
    )
    result = runner.invoke(app, ["ready"])
    assert result.exit_code == 0
    assert "ok" in result.output


@respx.mock
def test_config_client_json_mode(runner):
    respx.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(200, json={"flightSqlHost": "e", "flightSqlPort": 31338})
    )
    result = runner.invoke(app, ["--json", "config", "client"])
    assert result.exit_code == 0
    assert '"flightSqlPort": 31338' in result.output


@respx.mock
def test_config_server(runner):
    route = respx.get(f"{BASE}/api/config/server").mock(
        return_value=httpx.Response(200, json={})
    )
    assert runner.invoke(app, ["config", "server"]).exit_code == 0
    assert route.called


@respx.mock
def test_api_error_exits_1(runner):
    respx.get(f"{BASE}/health").mock(
        return_value=httpx.Response(500, json={"error": "boom", "message": "db down"})
    )
    result = runner.invoke(app, ["health"])
    assert result.exit_code == 1
    assert "db down" in result.output


def test_profile_flag_reads_named_profile(runner, respx_mock):
    from qod_cli.config import save_profile

    save_profile("prod", {"manager_url": "http://prod:1"})
    route = respx_mock.get("http://prod:1/health").mock(
        return_value=httpx.Response(200, json={"status": "ok"})
    )
    assert runner.invoke(app, ["--profile", "prod", "health"]).exit_code == 0
    assert route.called
