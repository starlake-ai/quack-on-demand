import httpx

from qod_cli.main import app

BASE = "http://localhost:20900"
YAML = "tenants:\n  - id: acme\n"


def test_export_to_stdout(runner, respx_mock):
    respx_mock.get(f"{BASE}/api/manifest/export").mock(return_value=httpx.Response(200, text=YAML))
    result = runner.invoke(app, ["manifest", "export"])
    assert result.exit_code == 0
    assert "id: acme" in result.output


def test_export_to_file(runner, respx_mock, tmp_path):
    respx_mock.get(f"{BASE}/api/manifest/export").mock(return_value=httpx.Response(200, text=YAML))
    target = tmp_path / "manifest.yaml"
    result = runner.invoke(app, ["manifest", "export", "--out", str(target)])
    assert result.exit_code == 0
    assert target.read_text() == YAML


def test_import_from_file(runner, respx_mock, tmp_path):
    route = respx_mock.post(f"{BASE}/api/manifest/import").mock(
        return_value=httpx.Response(200, json={"tenants": 1, "pools": 0})
    )
    source = tmp_path / "manifest.yaml"
    source.write_text(YAML)
    result = runner.invoke(app, ["manifest", "import", str(source)])
    assert result.exit_code == 0
    assert route.calls.last.request.content == YAML.encode()
