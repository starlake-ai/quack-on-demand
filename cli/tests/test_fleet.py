from typer.testing import CliRunner
from qod_cli.main import app
import qod_cli.rest as rest

runner = CliRunner()

def test_fleet_servers_and_ops(monkeypatch):
    calls = []
    def fake_request(self, method, path, params=None, body=None, text=False):
        calls.append((method, path, body))
        return {"servers": []} if method == "GET" else None
    monkeypatch.setattr(rest.RestClient, "request", fake_request)
    assert runner.invoke(app, ["fleet", "servers"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "drain", "srv-1"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "undrain", "srv-1"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "remove", "srv-1"]).exit_code == 0
    assert calls == [
        ("GET", "/api/fleet/servers", None),
        ("POST", "/api/fleet/server/drain", {"name": "srv-1"}),
        ("POST", "/api/fleet/server/undrain", {"name": "srv-1"}),
        ("POST", "/api/fleet/server/remove", {"name": "srv-1"}),
    ]
