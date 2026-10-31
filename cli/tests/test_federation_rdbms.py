"""Typed postgres / mysql flags on `qod federation create`.

The body assertions read the bytes httpx actually put on the wire (respx records the real
request), as test_federation_iceberg.py does, so a flag that never reaches `call()` cannot pass.
"""

import json

import httpx
import pytest

BASE = "http://localhost:20900"
SOURCES = f"{BASE}/api/tenants/acme/tenant-dbs/db1/federated-sources"


@pytest.fixture(autouse=True)
def token(monkeypatch):
    monkeypatch.setenv("QOD_TOKEN", "k")


def _routes(respx_mock, secret_status=200, existing_secrets=None):
    """Mock the create and the secret endpoints. Registered even by the tests that expect the CLI to
    refuse before calling, so a lost guard surfaces as an assertion rather than a real request.
    `existing_secrets` None = the source does not exist yet (the secret listing 404s)."""
    create = respx_mock.post(SOURCES).mock(return_value=httpx.Response(200, json={"alias": "x"}))
    secret = respx_mock.put(f"{SOURCES}/shop_pg/secrets").mock(
        return_value=httpx.Response(secret_status, json={"name": "PASSWORD"})
    )
    listing = (
        httpx.Response(404, json={"error": "not_found", "message": "source not found"})
        if existing_secrets is None
        else httpx.Response(
            200, json={"secrets": [{"name": n, "id": n, "federatedSourceId": "fs",
                                    "value": "***REDACTED***", "externalRef": None}
                                   for n in existing_secrets]}
        )
    )
    respx_mock.get(f"{SOURCES}/shop_pg/secrets").mock(return_value=listing)
    return create, secret


def _invoke(runner, *args, input=None):
    from qod_cli.main import app

    return runner.invoke(app, ["federation", "create", "acme", "db1", *args], input=input)


def _body(route):
    return json.loads(route.calls[-1].request.content.decode())


PG = ("--alias", "shop_pg", "--type", "postgres", "--host", "pg.example.com",
      "--database", "shop", "--user", "reader")


def test_postgres_flags_with_a_secret_reference(runner, respx_mock):
    create, secret = _routes(respx_mock)
    result = _invoke(runner, *PG, "--port", "6432", "--password", "{{secret.PG_PWD}}",
                     "--schema", "sales", "--ssl-mode", "require")
    assert result.exit_code == 0, result.output
    body = _body(create)
    assert body["sourceType"] == "postgres"
    assert body["connection"] == {
        "host": "pg.example.com",
        "port": 6432,
        "database": "shop",
        "user": "reader",
        "password": "{{secret.PG_PWD}}",
        "schema": "sales",
        "sslMode": "require",
    }
    assert "config" not in body and "setupSql" not in body
    # read-only is the manager's default for a typed source; the CLI sends nothing unless told.
    assert "readOnly" not in body
    assert not secret.called


def test_password_stdin_stores_the_value_as_a_secret_never_in_the_source(runner, respx_mock):
    create, secret = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password-stdin", input="s3cr3t'pw\n")
    assert result.exit_code == 0, result.output
    assert _body(create)["connection"]["password"] == "{{secret.PASSWORD}}"
    for c in create.calls:
        assert "s3cr3t" not in c.request.content.decode()
    assert _body(secret) == {"name": "PASSWORD", "value": "s3cr3t'pw"}
    assert secret.call_count == 1


def test_a_new_source_is_saved_disabled_until_its_secret_exists(runner, respx_mock):
    """An enabled source naming a missing secret fails the whole tenant-db's blob at spawn, so a new
    source goes disabled -> secret -> as asked (enabled)."""
    create, secret = _routes(respx_mock)
    order = []
    create.side_effect = lambda req: (order.append(("POST", json.loads(req.content)["disabled"])),
                                      httpx.Response(200, json={"alias": "x"}))[1]
    secret.side_effect = lambda req: (order.append(("PUT", None)),
                                      httpx.Response(200, json={"name": "PASSWORD"}))[1]
    result = _invoke(runner, *PG, "--password-stdin", input="pw\n")
    assert result.exit_code == 0, result.output
    assert order == [("POST", True), ("PUT", None), ("POST", False)]


def test_an_existing_source_gets_its_secret_first_and_is_never_disabled(runner, respx_mock):
    """Rotation, or a move to a secret name the source does not hold yet: the secret is written
    first, so the placeholder resolves at every instant and the live source is never disabled."""
    for secrets, name in ((["PASSWORD"], "PASSWORD"), (["OLD"], "NEW")):
        respx_mock.reset()
        create, secret = _routes(respx_mock, existing_secrets=secrets)
        order = []
        create.side_effect = lambda req: (
            order.append(("POST", json.loads(req.content)["disabled"])),
            httpx.Response(200, json={"alias": "x"}))[1]
        secret.side_effect = lambda req: (order.append(("PUT", json.loads(req.content)["name"])),
                                          httpx.Response(200, json={"name": "x"}))[1]
        result = _invoke(runner, *PG, "--password-stdin", "--password-secret", name,
                         input="new-pw\n")
        assert result.exit_code == 0, result.output
        assert order == [("PUT", name), ("POST", False)], (secrets, name)


def test_a_failing_source_lookup_stops_instead_of_guessing(runner, respx_mock):
    create, secret = _routes(respx_mock)
    respx_mock.get(f"{SOURCES}/shop_pg/secrets").mock(
        return_value=httpx.Response(500, json={"error": "boom", "message": "down"})
    )
    result = _invoke(runner, *PG, "--password-stdin", input="pw\n")
    assert result.exit_code != 0
    assert not create.called and not secret.called


def test_a_literal_password_flag_is_refused(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password", "hunter2")
    assert result.exit_code != 0
    assert "shell history" in result.output
    assert not create.called


def test_a_password_is_required(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, *PG)
    assert result.exit_code != 0
    assert "needs a password" in result.output
    assert not create.called


def test_host_database_user_are_required(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, "--alias", "shop_pg", "--type", "postgres",
                     "--password", "{{secret.X}}")
    assert result.exit_code != 0
    assert "--host" in result.output and "--database" in result.output
    assert not create.called


def test_whole_connection_json_wins(runner, respx_mock):
    create, _ = _routes(respx_mock)
    conn = {"host": "h", "database": "d", "user": "u", "password": "{{secret.P}}"}
    result = _invoke(runner, "--alias", "shop_pg", "--type", "mysql",
                     "--connection", json.dumps(conn))
    assert result.exit_code == 0, result.output
    body = _body(create)
    assert body["sourceType"] == "mysql"
    assert body["connection"] == conn


def test_connection_flags_are_refused_for_other_types(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, "--alias", "x", "--setup-sql", "ATTACH 'x';", "--host", "h")
    assert result.exit_code != 0
    assert "--type postgres or mysql" in result.output
    assert not create.called


def test_setup_sql_is_refused_for_postgres(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password", "{{secret.X}}", "--setup-sql", "ATTACH 'x';")
    assert result.exit_code != 0
    assert not create.called


def test_a_failed_secret_write_leaves_the_source_disabled_and_says_how_to_finish(runner, respx_mock):
    create, secret = _routes(respx_mock, secret_status=400)
    result = _invoke(runner, *PG, "--password-stdin", input="pw\n")
    assert result.exit_code != 0
    assert create.call_count == 1 and _body(create)["disabled"] is True
    assert secret.called
    assert "DISABLED" in result.output
    assert "qod federation secret set acme db1 shop_pg --name PASSWORD" in result.output


def test_connection_json_cannot_be_combined_with_other_connection_flags(runner, respx_mock):
    create, secret = _routes(respx_mock)
    conn = json.dumps({"host": "h", "database": "d", "user": "u", "password": "{{secret.OLD}}"})
    for extra in (["--password-stdin"], ["--host", "other"], ["--port", "1"]):
        result = _invoke(runner, "--alias", "shop_pg", "--type", "postgres",
                         "--connection", conn, *extra, input="pw\n")
        assert result.exit_code != 0, extra
        assert "--connection carries the whole connection" in result.output
    assert not create.called and not secret.called
