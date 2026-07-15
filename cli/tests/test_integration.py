"""Live smoke test against a running local manager. Opt in with QOD_IT=1.

Boot the stack first (see CLAUDE.md): ./scripts/run-jar.sh with demo data
loaded, default admin/admin credentials.
"""

import os

import pytest
from typer.testing import CliRunner

from qod_cli.main import app

pytestmark = pytest.mark.skipif(os.environ.get("QOD_IT") != "1", reason="QOD_IT=1 not set")

runner = CliRunner()


@pytest.fixture(autouse=True)
def live_config(tmp_path, monkeypatch):
    monkeypatch.setenv("QOD_CONFIG_FILE", str(tmp_path / "config.toml"))


def test_login_list_query():
    result = runner.invoke(app, ["login", "--username", "admin"], input="admin\n")
    assert result.exit_code == 0, result.output

    result = runner.invoke(app, ["--json", "tenant", "list"])
    assert result.exit_code == 0, result.output
    assert "acme" in result.output

    result = runner.invoke(
        app,
        ["sql", "SELECT 1 AS one", "--tenant", "acme", "--pool", "bi", "--superuser", "--csv"],
        env={"QOD_PASSWORD": "admin"},
    )
    assert result.exit_code == 0, result.output
    assert "one" in result.output
