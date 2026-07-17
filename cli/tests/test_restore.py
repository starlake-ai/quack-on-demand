"""qod catalog restore: dry-run, confirm prompt, --yes, and the two-call execute flow."""

import json

import httpx
import respx

from qod_cli.main import app

BASE = "http://localhost:20900"

DRY = {
    "schema": "tpch1", "table": "orders", "toSnapshot": 480, "currentSnapshot": 512,
    "summary": {"inserted": 3, "deleted": 1, "updated": 2}, "dryRun": True,
}
DONE = {
    "schema": "tpch1", "table": "orders", "toSnapshot": 480, "currentSnapshot": 512,
    "newSnapshot": 513, "dryRun": False,
}

ARGS = ["catalog", "restore", "--tenant", "acme", "--db", "tpch1",
        "--schema", "tpch1", "--table", "orders", "--to", "480"]


@respx.mock
def test_dry_run_is_a_single_call(runner):
    route = respx.post(f"{BASE}/api/catalog/restore").mock(
        return_value=httpx.Response(200, json=DRY)
    )
    result = runner.invoke(app, ["--json"] + ARGS + ["--dry-run"])
    assert result.exit_code == 0, result.output
    assert route.call_count == 1
    body = json.loads(route.calls[0].request.content)
    assert body == {"tenant": "acme", "tenantDb": "tpch1", "schema": "tpch1",
                    "table": "orders", "to": "480", "dryRun": True}


@respx.mock
def test_yes_executes_with_expected_current_snapshot(runner):
    route = respx.post(f"{BASE}/api/catalog/restore").mock(
        side_effect=[httpx.Response(200, json=DRY), httpx.Response(200, json=DONE)]
    )
    result = runner.invoke(app, ["--json"] + ARGS + ["--yes"])
    assert result.exit_code == 0, result.output
    assert route.call_count == 2
    execute = json.loads(route.calls[1].request.content)
    assert execute["expectedCurrentSnapshot"] == 512
    assert "dryRun" not in execute


@respx.mock
def test_declined_confirm_does_not_execute(runner):
    route = respx.post(f"{BASE}/api/catalog/restore").mock(
        return_value=httpx.Response(200, json=DRY)
    )
    result = runner.invoke(app, ARGS, input="n\n")
    assert result.exit_code == 1
    assert route.call_count == 1
