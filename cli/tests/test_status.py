import json

BASE = "http://localhost:20900"


def _mock_healthy(respx_mock, ready_code=200, pools=2, nodes=5):
    respx_mock.get(f"{BASE}/health").mock(
        return_value=__import__("httpx").Response(
            200, json={"status": "ok", "poolsCount": pools, "nodesCount": nodes}
        )
    )
    respx_mock.get(f"{BASE}/ready").mock(
        return_value=__import__("httpx").Response(ready_code, json={"status": "ok"})
    )
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=__import__("httpx").Response(
            200,
            json={"flightSqlHost": "0.0.0.0", "flightSqlPort": 31338, "flightSqlTls": True},
        )
    )


def _quiet_local(monkeypatch, tcp=False):
    from qod_cli.commands import status as status_mod

    monkeypatch.setattr(status_mod, "_listening_pid", lambda port: None)
    monkeypatch.setattr(status_mod, "_tcp_open", lambda host, port: tcp)


def test_status_up(runner, respx_mock, monkeypatch):
    from qod_cli.main import app

    _mock_healthy(respx_mock)
    _quiet_local(monkeypatch, tcp=True)
    result = runner.invoke(app, ["status"])
    assert result.exit_code == 0, result.output
    assert "up" in result.output
    assert "localhost:31338" in result.output  # 0.0.0.0 substituted by manager host


def test_status_not_ready_still_exit_zero(runner, respx_mock, monkeypatch):
    from qod_cli.main import app

    _mock_healthy(respx_mock, ready_code=503)
    _quiet_local(monkeypatch)
    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.stdout)
    assert payload["manager"] == "up"
    assert payload["ready"] is False
    assert payload["pools"] == 2


def test_status_unreachable_exits_one(runner, respx_mock, monkeypatch):
    import httpx

    from qod_cli.main import app

    respx_mock.get(f"{BASE}/health").mock(side_effect=httpx.ConnectError("refused"))
    respx_mock.get(f"{BASE}/ready").mock(side_effect=httpx.ConnectError("refused"))
    respx_mock.get(f"{BASE}/api/config/client").mock(side_effect=httpx.ConnectError("refused"))
    _quiet_local(monkeypatch)
    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 1
    payload = json.loads(result.stdout)
    assert payload["manager"] == "unreachable"


def test_status_pool_detail_when_authenticated(runner, respx_mock, monkeypatch):
    import httpx

    from qod_cli.config import save_profile
    from qod_cli.main import app

    save_profile("default", {"token": "jwt-abc"})
    _mock_healthy(respx_mock)
    _quiet_local(monkeypatch)
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(
            200,
            json={
                "pools": [
                    {
                        "tenant": "acme",
                        "pool": "bi",
                        "nodes": [{"healthy": True}, {"healthy": False}],
                    }
                ]
            },
        )
    )
    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.stdout)
    assert payload["poolDetail"] == [
        {"tenant": "acme", "pool": "bi", "nodesHealthy": 1, "nodesTotal": 2}
    ]


def test_status_auth_failure_degrades_silently(runner, respx_mock, monkeypatch):
    import httpx

    from qod_cli.config import save_profile
    from qod_cli.main import app

    save_profile("default", {"token": "expired"})
    _mock_healthy(respx_mock)
    _quiet_local(monkeypatch)
    respx_mock.get(f"{BASE}/api/pool/list").mock(return_value=httpx.Response(401, json={}))
    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.stdout)
    assert "poolDetail" not in payload
    assert payload["pools"] == 2  # unauthenticated summary still present
