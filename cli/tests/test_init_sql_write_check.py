"""Task 20 Part D on the CLI: a tenant admin's refused initSql is the call's own error (400
init_sql_refused, naming each statement's position and reason), and an initSql the manager could not
check comes back with a warning the CLI prints on stderr."""

import httpx
import pytest

BASE = "http://localhost:20900"
REFUSED = ("initSql holds 1 statement(s) a tenant admin may not run at node start: "
           "statement 2 of 2: COPY is not allowed in a tenant initSql")
WARNING = "this manager runs no qod-node of its own, so the initSql was not checked here"


@pytest.fixture(autouse=True)
def token(monkeypatch):
    monkeypatch.setenv("QOD_TOKEN", "k")


def _invoke(runner, *args):
    from qod_cli.main import app

    return runner.invoke(app, list(args))


def test_a_refused_init_sql_is_the_error(runner, respx_mock):
    respx_mock.post(f"{BASE}/api/database/create").mock(
        return_value=httpx.Response(400, json={"error": "init_sql_refused", "message": REFUSED})
    )
    result = _invoke(runner, "database", "create", "--tenant", "acme", "--name", "sales",
                     "--kind", "memory", "--init-sql", "SET threads = 2; COPY (SELECT 1) TO '/tmp/x';")
    assert result.exit_code == 1
    assert "statement 2 of 2: COPY is not allowed" in result.output


@pytest.mark.parametrize("args, path, payload", [
    (["database", "create", "--tenant", "acme", "--name", "sales", "--kind", "memory",
      "--init-sql", "SET threads = 2;"], "/api/database/create",
     {"name": "acme_sales", "initSqlWarning": WARNING}),
    (["database", "update", "--tenant", "acme", "--name", "acme_sales", "--init-sql", "SET threads = 2;"],
     "/api/database/update", {"db": {"name": "acme_sales", "initSqlWarning": WARNING}}),
    (["pool", "create", "--tenant", "acme", "--db", "acme_sales", "--pool", "bi", "--size", "1",
      "--dual", "1", "--init-sql", "SET threads = 2;"], "/api/pool/create",
     {"pool": "bi", "initSqlWarning": WARNING}),
])
def test_an_unchecked_init_sql_warns_on_stderr(runner, respx_mock, args, path, payload):
    respx_mock.post(f"{BASE}{path}").mock(return_value=httpx.Response(200, json=payload))
    result = _invoke(runner, *args)
    assert result.exit_code == 0, result.output
    assert f"warning: {WARNING}" in result.output


def test_no_warning_without_one(runner, respx_mock):
    respx_mock.post(f"{BASE}/api/database/create").mock(
        return_value=httpx.Response(200, json={"name": "acme_sales", "initSqlWarning": None})
    )
    result = _invoke(runner, "database", "create", "--tenant", "acme", "--name", "sales", "--kind", "memory")
    assert result.exit_code == 0, result.output
    assert "warning:" not in result.output
