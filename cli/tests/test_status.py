import json
import socket
from pathlib import Path

import httpx

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


def _stub_listening_pid(monkeypatch):
    """Like _quiet_local but leaves _tcp_open real - the embedded-postgres tests
    below reuse _tcp_open to probe an actual socket they bind themselves."""
    from qod_cli.commands import status as status_mod

    monkeypatch.setattr(status_mod, "_listening_pid", lambda port: None)


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


def _write_postmaster_pid(pgdata: Path, port: int) -> None:
    """A trimmed real postmaster.pid: line 1 pid, line 2 data dir, line 3 start
    time, line 4 port (the fields `qod status` actually reads)."""
    pgdata.mkdir(parents=True, exist_ok=True)
    (pgdata / "postmaster.pid").write_text(
        f"12345\n{pgdata}\n1700000000\n{port}\n\n\n\n"
    )


def _mock_embedded_probe(respx_mock, pools=0, nodes=0):
    respx_mock.get(f"{BASE}/health").mock(
        return_value=httpx.Response(
            200, json={"poolsCount": pools, "nodesCount": nodes}
        )
    )
    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200))
    respx_mock.get(f"{BASE}/api/config/client").mock(return_value=httpx.Response(404))


def test_status_reports_a_running_embedded_control_plane(
    runner, respx_mock, monkeypatch, tmp_path
):
    from qod_cli.main import app

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("localhost", 0))
    listener.listen(1)
    try:
        port = listener.getsockname()[1]
        pg_dir = tmp_path / "pg"
        _write_postmaster_pid(pg_dir / "pgdata", port)
        monkeypatch.setenv("QOD_PG_EMBEDDED_DATA_DIR", str(pg_dir))
        # Only _listening_pid is stubbed here (unlike _quiet_local elsewhere in this
        # file): _tcp_open must stay real so it actually probes the listener above.
        _stub_listening_pid(monkeypatch)
        _mock_embedded_probe(respx_mock, pools=1, nodes=1)

        result = runner.invoke(app, ["--json", "status"])
        assert result.exit_code == 0, result.output
        payload = json.loads(result.stdout)
        assert payload["embeddedPostgres"] == f"running (localhost:{port})"
        assert payload["embeddedPostgresDir"] == str(pg_dir)
    finally:
        listener.close()


def test_status_reports_a_stopped_embedded_control_plane(
    runner, respx_mock, monkeypatch, tmp_path
):
    from qod_cli.main import app

    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    probe.bind(("localhost", 0))
    port = probe.getsockname()[1]
    probe.close()  # closed again: nothing is listening on it, port stays a real int

    pg_dir = tmp_path / "pg"
    _write_postmaster_pid(pg_dir / "pgdata", port)
    monkeypatch.setenv("QOD_PG_EMBEDDED_DATA_DIR", str(pg_dir))
    # _tcp_open stays real here: it must see that the port above is closed.
    _stub_listening_pid(monkeypatch)
    _mock_embedded_probe(respx_mock)

    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.stdout)
    assert payload["embeddedPostgres"] == "stopped (data preserved)"
    assert payload["embeddedPostgresDir"] == str(pg_dir)


def test_status_finds_the_embedded_dir_persisted_by_qod_setup(
    runner, respx_mock, monkeypatch, tmp_path
):
    """qod serve resolves its data dir from {**load_start_env(), **os.environ} (a
    real env var wins, but a value `qod setup --set` persisted to [start] is found
    too) - status must use the same precedence, or a value persisted that way is
    invisible to it in a fresh shell with no matching env var set."""
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    probe.bind(("localhost", 0))
    port = probe.getsockname()[1]
    probe.close()

    pg_dir = tmp_path / "pg"
    _write_postmaster_pid(pg_dir / "pgdata", port)
    save_start_env({"QOD_PG_EMBEDDED_DATA_DIR": str(pg_dir)})
    # Deliberately no monkeypatch.setenv: the value must be found via load_start_env.
    _stub_listening_pid(monkeypatch)
    _mock_embedded_probe(respx_mock)

    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.stdout)
    assert payload["embeddedPostgres"] == "stopped (data preserved)"
    assert payload["embeddedPostgresDir"] == str(pg_dir)


def test_status_omits_the_embedded_line_for_an_external_postgres(
    runner, respx_mock, monkeypatch, tmp_path
):
    from qod_cli.main import app

    # No pgdata under this dir at all: an external Postgres, nothing to probe.
    monkeypatch.setenv("QOD_PG_EMBEDDED_DATA_DIR", str(tmp_path / "pg"))
    _quiet_local(monkeypatch)
    _mock_embedded_probe(respx_mock)

    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    payload = json.loads(result.stdout)
    assert "embeddedPostgres" not in payload
    assert "embeddedPostgresDir" not in payload


def test_status_never_lists_pools_with_a_qod_api_key(runner, respx_mock, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setenv("QOD_API_KEY", "stale-key-value")
    _mock_healthy(respx_mock)
    _quiet_local(monkeypatch)
    pools = respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": []})
    )
    result = runner.invoke(app, ["--json", "status"])
    assert result.exit_code == 0, result.output
    assert not pools.called
    assert "poolDetail" not in json.loads(result.stdout)
