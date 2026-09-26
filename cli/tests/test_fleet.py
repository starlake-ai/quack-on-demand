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


def _pools_with_nodes():
    node = lambda nid, server, state: {
        "nodeId": nid, "role": "Dual", "host": "127.0.0.1", "port": 23101, "healthy": True,
        "quarantined": False, "inFlight": 0, "serverName": server, "serverState": state,
    }
    return {"pools": [
        {"tenant": "acme", "tenantDb": "acme_tpch", "pool": "bi",
         "nodes": [node("quack-acme-acme-tpch-bi-1", "s1", "reachable"), node("quack-acme-acme-tpch-bi-2", "s2", "dead")]},
        {"tenant": "globex", "tenantDb": "globex_db", "pool": "etl",
         "nodes": [node("quack-globex-globex-db-etl-1", None, None)]},
    ]}


def test_node_list_flattens_pools_with_a_server_column(monkeypatch):
    import json
    calls = []
    def fake_request(self, method, path, params=None, body=None, text=False):
        calls.append((method, path))
        return _pools_with_nodes()
    monkeypatch.setattr(rest.RestClient, "request", fake_request)
    out = runner.invoke(app, ["--json", "node", "list"])
    assert out.exit_code == 0, out.output
    rows = json.loads(out.output)
    assert calls == [("GET", "/api/pool/list")]
    assert [(r["node"], r["server"], r["serverState"]) for r in rows] == [
        ("quack-acme-acme-tpch-bi-1", "s1", "reachable"),
        ("quack-acme-acme-tpch-bi-2", "s2", "dead"),
        ("quack-globex-globex-db-etl-1", None, None),
    ]
    assert rows[0]["tenant"] == "acme" and rows[0]["db"] == "acme_tpch" and rows[0]["pool"] == "bi"
    table = runner.invoke(app, ["node", "list", "--tenant", "acme"])
    assert table.exit_code == 0 and "s2" in table.output and "globex" not in table.output
