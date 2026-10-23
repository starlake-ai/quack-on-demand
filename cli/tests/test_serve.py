import pathlib
import time

import httpx
from click import unstyle
import pytest
import respx

from qod_cli import launcher
from qod_cli.config import load_settings, load_start_env
from tests import _duckdb_cache

_REAL_ENSURE_CLI, _REAL_ENSURE_LIB = launcher.ensure_duckdb_cli, launcher.ensure_libduckdb

BASE = "http://localhost:20900"


@pytest.fixture
def wired(monkeypatch, tmp_path):
    """Stub provisioning and capture the exec, so no JVM or Postgres is launched."""
    from qod_cli.commands import serve as serve_cmd

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    captured = {}
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(
        launcher, "ensure_duckdb_cli", lambda cache_dir=None, **kw: tmp_path / "duckdb" / launcher.DUCKDB_CLI_VERSION / "bin"
    )
    monkeypatch.setattr(
        launcher, "ensure_libduckdb", lambda cache_dir=None, **kw: tmp_path / "duckdb" / launcher.DUCKDB_CLI_VERSION / "lib"
    )
    monkeypatch.setattr(launcher, "node_binary", lambda *a, **kw: None)
    monkeypatch.setattr(
        launcher, "materialize_spawn_scripts", lambda dest: (tmp_path / "s.sh", tmp_path / "s.ps1")
    )
    monkeypatch.setattr(launcher, "default_data_dir", lambda: tmp_path / "state")
    monkeypatch.setattr(launcher, "default_cache_dir", lambda: tmp_path / "cache")

    def fake_exec(cmd, env=None):
        captured["cmd"], captured["env"] = cmd, env

    monkeypatch.setattr(serve_cmd, "_exec", fake_exec)
    # The provisioning thread would otherwise poll a manager that never boots.
    monkeypatch.setattr(serve_cmd, "_spawn_provisioning", lambda **kw: captured.setdefault("provision", kw))
    monkeypatch.setattr(serve_cmd.os, "chdir", lambda d: captured.setdefault("cwd", d))
    from qod_cli import admin_password as ap

    monkeypatch.setattr(ap, "is_interactive", lambda: False)
    # A later boot by default (the embedded pgdata exists), so tests about other
    # behavior need no admin password; the first-boot tests remove it.
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True)
    captured["jar"] = jar
    captured["tmp"] = tmp_path
    return captured


def _first_boot(wired):
    (wired["tmp"] / "state" / "pg" / "pgdata").rmdir()


def _invoke(runner, wired, *args):
    from qod_cli.main import app

    return runner.invoke(app, ["serve", *args, "--jar", str(wired["jar"])])


def test_serve_echoes_a_staleness_hint_when_present(runner, wired, tmp_path, monkeypatch):
    from qod_cli import launcher

    monkeypatch.setattr(launcher, "newer_release_hint", lambda current: f"note: newer than {current}")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert "note: newer than" in result.output


def test_serve_stays_silent_without_a_staleness_hint(runner, wired, tmp_path, monkeypatch):
    from qod_cli import launcher

    monkeypatch.setattr(launcher, "newer_release_hint", lambda current: None)
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert "note:" not in result.output


def test_serve_sets_the_embedded_control_plane_env(runner, wired, tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    env = wired["env"]
    assert env["QOD_PG_EMBEDDED"] == "true"
    assert env["QOD_PG_EMBEDDED_PORT"] == "25432"
    assert env["QOD_PG_EMBEDDED_DATA_DIR"] == str(tmp_path / "state" / "pg")


def test_serve_hands_the_jvm_a_preloadable_libduckdb(runner, wired, tmp_path, monkeypatch):
    # The real ensure_* over a provisioned cache: DUCKDB_CACHE_DIR must name it, since a
    # SIP-protected /usr/bin/java never sees DYLD_LIBRARY_PATH.
    monkeypatch.setattr(launcher, "ensure_duckdb_cli", _REAL_ENSURE_CLI)
    monkeypatch.setattr(launcher, "ensure_libduckdb", _REAL_ENSURE_LIB)
    _, lib_dir = _duckdb_cache.plant(tmp_path / "cache")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    with respx.mock:  # no routes: a provisioned cache must not hit the network
        result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    target = _duckdb_cache.preload_target(wired["env"])
    assert target.is_file() and target.parent == lib_dir


def test_serve_turns_acl_on_explicitly(runner, wired, tmp_path):
    # quack-on-demand.acl.enabled defaults to FALSE in application.conf, so a
    # persistent install has to ask for it.
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_ACL_ENABLED"] == "true"


def test_serve_first_boot_refuses_without_terminal_or_env(runner, wired, tmp_path):
    _first_boot(wired)
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert "first boot needs an admin password; run qod serve" in result.output
    assert "cmd" not in wired


def test_serve_first_boot_takes_the_env_password_and_never_stores_it(
    runner, wired, tmp_path, monkeypatch
):
    _first_boot(wired)
    monkeypatch.setenv("QOD_ADMIN_PASSWORD", "from-env")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_ADMIN_PASSWORD"] == "from-env"
    assert "QOD_ADMIN_PASSWORD" not in load_start_env()
    assert wired["provision"]["password"] == "from-env"
    assert wired["provision"]["first_boot"] is True


def test_serve_first_boot_prompts_on_a_terminal(runner, wired, tmp_path, monkeypatch):
    from qod_cli import admin_password as ap
    from qod_cli.main import app

    _first_boot(wired)
    monkeypatch.setattr(ap, "is_interactive", lambda: True)
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = runner.invoke(
        app, ["serve", str(f), "--jar", str(wired["jar"])], input="pw1\npw1\n"
    )
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_ADMIN_PASSWORD"] == "pw1"


def test_serve_later_boot_needs_no_password_and_provisions_with_an_api_key(
    runner, wired, tmp_path
):
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True, exist_ok=True)
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert "QOD_ADMIN_PASSWORD" not in wired["env"]
    key = wired["env"]["QOD_API_KEY"]
    assert len(key) >= 32
    assert wired["provision"]["api_key"] == key
    assert wired["provision"]["password"] is None
    assert "QOD_API_KEY" not in load_start_env()


def test_serve_keeps_a_configured_api_key(runner, wired, tmp_path, monkeypatch):
    (tmp_path / "state" / "pg" / "pgdata").mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("QOD_API_KEY", "mine")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["provision"]["api_key"] == "mine"


def test_serve_hands_every_configured_admin_name_to_the_banner(
    runner, wired, tmp_path, monkeypatch
):
    monkeypatch.setenv("QOD_ADMIN_USERNAME", "ops,root")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["provision"]["admin_names"] == ["ops", "root"]


def test_serve_passes_the_resolved_target_to_provisioning(runner, wired, tmp_path):
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    target = wired["provision"]["target"]
    assert target.kind == "duckdb-file"
    assert target.name == "sales"
    assert target.metastore == {"dbName": "sales", "schemaName": "main"}
    assert wired["provision"]["tenant"] == "default"
    assert wired["provision"]["pool"] == "bi"


def test_serve_honors_the_port_and_data_dir_flags(runner, wired, tmp_path, monkeypatch):
    # /custom/pg holds no pgdata, so this is a first boot there.
    monkeypatch.setenv("QOD_ADMIN_PASSWORD", "pw")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f), "--pg-port", "26000", "--pg-data-dir", "/custom/pg")
    assert wired["env"]["QOD_PG_EMBEDDED_PORT"] == "26000"
    assert wired["env"]["QOD_PG_EMBEDDED_DATA_DIR"] == "/custom/pg"


def test_serve_honors_pg_port_env_var_over_default(runner, wired, tmp_path, monkeypatch):
    # M-3: explicit flag > env var > persisted qod-setup value > default. With no
    # flag, a real QOD_PG_EMBEDDED_PORT must not be clobbered by the flag's default.
    monkeypatch.setenv("QOD_PG_EMBEDDED_PORT", "26500")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_PG_EMBEDDED_PORT"] == "26500"


def test_serve_canonicalizes_tenant_case(runner, wired, tmp_path):
    # F4: the server stores tenants lowercase (HandlerResolvers.resolveTenantId).
    # Keeping the raw case through ensure_pool's client-side match, the banner,
    # the JDBC string, and the saved profile means a re-run with mixed case misses
    # the existing pool and hits a server "already exists" error instead of a noop.
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f), "--tenant", "Acme")
    assert wired["provision"]["tenant"] == "acme"


def test_serve_rejects_a_non_numeric_pg_port_cleanly(runner, wired, tmp_path, monkeypatch):
    # F6/N1: a malformed QOD_PG_EMBEDDED_PORT (env or a `qod setup` typo) must be a
    # clean pre-flight refusal, not a raw ValueError traceback.
    monkeypatch.setenv("QOD_PG_EMBEDDED_PORT", "not-a-port")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert "QOD_PG_EMBEDDED_PORT" in result.output
    assert "not-a-port" in result.output
    assert "Traceback" not in result.output
    assert "cmd" not in wired


def test_serve_falls_back_to_the_default_pg_data_dir_on_an_empty_env_value(
    runner, wired, tmp_path, monkeypatch
):
    # F6/N2: an empty-string QOD_PG_EMBEDDED_DATA_DIR (a plausible `export
    # QOD_PG_EMBEDDED_DATA_DIR=` typo) must fall to the built-in default, not land
    # the pgdata in cwd-relative nonsense.
    monkeypatch.setenv("QOD_PG_EMBEDDED_DATA_DIR", "")
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_PG_EMBEDDED_DATA_DIR"] == str(tmp_path / "state" / "pg")


def test_serve_warns_loudly_when_acl_is_overridden_off(runner, wired, tmp_path):
    # I-1: a QOD_ACL_ENABLED=false persisted by `qod setup` outranks serve's
    # setdefault silently today; it must at least warn.
    from qod_cli.config import save_start_env

    save_start_env({"QOD_ACL_ENABLED": "false"})
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert wired["env"]["QOD_ACL_ENABLED"] == "false"
    assert "WARN" in result.output
    assert "QOD_ACL_ENABLED=false" in result.output


def test_serve_rejects_size_greater_than_1_for_a_duckdb_file(runner, wired, tmp_path):
    # M-1: DuckDB's single-writer file lock means only one node can attach a
    # .duckdb file read-write.
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f), "--size", "2")
    assert result.exit_code == 1
    assert "single-writer" in result.output
    assert "cmd" not in wired


def test_serve_fails_before_launching_on_a_missing_target(runner, wired, tmp_path):
    result = _invoke(runner, wired, str(tmp_path / "nope.duckdb"))
    assert result.exit_code == 1
    assert "does not exist" in result.output
    assert "cmd" not in wired


def test_serve_fails_before_launching_when_the_name_equals_the_tenant(runner, wired, tmp_path):
    # Names.normalizeTenantDbName refuses suffix == tenant (DuckDB cannot attach a
    # catalog under an existing catalog's name). Catch it before booting a JVM.
    f = tmp_path / "default.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert "--name" in result.output
    assert "cmd" not in wired


def test_serve_collects_s3_credentials_from_flags(runner, wired):
    _invoke(
        runner, wired, "s3://bucket/sales/",
        "--access-key-id", "AK", "--secret-access-key", "SK", "--region", "eu-west-1",
    )
    target = wired["provision"]["target"]
    assert target.object_store == {
        "s3_access_key_id": "AK",
        "s3_secret_access_key": "SK",
        "s3_region": "eu-west-1",
    }
    assert target.data_path == "s3://bucket/sales/"


def test_serve_falls_back_to_ambient_aws_env(runner, wired, monkeypatch):
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "envAK")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "envSK")
    _invoke(runner, wired, "s3://bucket/sales/")
    assert wired["provision"]["target"].object_store["s3_access_key_id"] == "envAK"


def test_serve_gs_target_uses_gcs_hmac_credentials(runner, wired):
    # F1: ObjectStoreSecret.sql reads gcs_hmac_key_id/gcs_hmac_secret for a gs://
    # dataPath, not the s3_* vocabulary.
    result = _invoke(
        runner, wired, "gs://bucket/sales/",
        "--access-key-id", "GOOGID", "--secret-access-key", "GOOGSECRET",
    )
    assert result.exit_code == 0, result.output
    target = wired["provision"]["target"]
    assert target.object_store == {
        "gcs_hmac_key_id": "GOOGID",
        "gcs_hmac_secret": "GOOGSECRET",
    }


def test_serve_gs_target_has_no_aws_env_fallback(runner, wired, monkeypatch):
    # gs has no equivalent to the AWS_* convention; ambient AWS_* credentials must
    # not leak into a gcs secret, and the empty result must be flagged, not silent.
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "envAK")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "envSK")
    result = _invoke(runner, wired, "gs://bucket/sales/")
    assert result.exit_code == 0, result.output
    assert wired["provision"]["target"].object_store == {}
    assert "note: no object-store credentials given for gs" in result.output


def test_serve_s3_target_drops_a_region_only_map_from_ambient_env(runner, wired, monkeypatch):
    # Round 2 item 1: a region-only map (no key, no secret) would still produce a
    # scoped CREATE SECRET server-side with KEY_ID ''/SECRET '', which OUTRANKS
    # DuckDB's ambient credential chain for exactly the served prefix - the empty-
    # credential trap F1 exists to prevent. AWS_REGION alone must not survive.
    # conftest's isolated_env strips AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY too, so
    # a real key in the running shell cannot leak into this test.
    monkeypatch.setenv("AWS_REGION", "eu-west-1")
    result = _invoke(runner, wired, "s3://bucket/sales/")
    assert result.exit_code == 0, result.output
    assert wired["provision"]["target"].object_store == {}
    assert "note: no object-store credentials given for s3" in result.output


def test_serve_s3_target_keeps_region_when_keys_are_present(runner, wired):
    result = _invoke(
        runner, wired, "s3://bucket/sales/",
        "--access-key-id", "AK", "--secret-access-key", "SK", "--region", "eu-west-1",
    )
    assert result.exit_code == 0, result.output
    target = wired["provision"]["target"]
    assert target.object_store == {
        "s3_access_key_id": "AK",
        "s3_secret_access_key": "SK",
        "s3_region": "eu-west-1",
    }
    assert "note: no object-store credentials" not in result.output


def test_serve_gs_target_refuses_a_single_credential_flag(runner, wired):
    # Round 2 item 2: gs must mirror az's both-or-neither pairing refusal.
    result = _invoke(runner, wired, "gs://bucket/sales/", "--access-key-id", "GOOGID")
    assert result.exit_code == 1
    assert "BOTH" in result.output
    assert "cmd" not in wired

    result = _invoke(
        runner, wired, "gs://bucket/sales/", "--secret-access-key", "GOOGSECRET"
    )
    assert result.exit_code == 1
    assert "BOTH" in result.output
    assert "cmd" not in wired


def test_serve_gs_target_refuses_region(runner, wired):
    # The gcs secret ObjectStoreSecret emits has no region field.
    result = _invoke(runner, wired, "gs://bucket/sales/", "--region", "eu-west-1")
    assert result.exit_code == 1
    assert "gs://" in result.output
    assert "cmd" not in wired


def test_serve_az_target_maps_both_flags_to_the_azure_account_pair(runner, wired):
    result = _invoke(
        runner, wired, "az://container/sales/",
        "--access-key-id", "myaccount", "--secret-access-key", "myaccountkey",
    )
    assert result.exit_code == 0, result.output
    target = wired["provision"]["target"]
    assert target.object_store == {
        "azure_account": "myaccount",
        "azure_account_key": "myaccountkey",
    }


def test_serve_az_target_refuses_a_single_credential_flag(runner, wired):
    result = _invoke(runner, wired, "az://container/sales/", "--access-key-id", "myaccount")
    assert result.exit_code == 1
    assert "BOTH" in result.output
    assert "cmd" not in wired


def test_serve_gcs_alias_normalizes_end_to_end(runner, wired):
    result = _invoke(runner, wired, "gcs://bucket/sales/")
    assert result.exit_code == 0, result.output
    assert wired["provision"]["target"].data_path.startswith("gs://")


def test_serve_credential_tail_note_for_local_anchor_with_remote_table(runner, wired, tmp_path):
    d = tmp_path / "sales"
    d.mkdir()
    result = _invoke(
        runner, wired, str(d), "--table", "orders=s3://bucket/orders/**/*.parquet",
    )
    assert result.exit_code == 0, result.output
    assert "different object-store scheme" in result.output
    assert "anchor: local" in result.output
    assert "s3" in result.output


def test_serve_credential_tail_note_names_the_mismatch_for_a_remote_anchor(runner, wired):
    result = _invoke(
        runner, wired, "s3://bucket/sales/",
        "--table", "orders=gs://other-bucket/orders/**/*.parquet",
    )
    assert result.exit_code == 0, result.output
    assert "anchor: s3" in result.output
    assert "gs" in result.output


def test_serve_no_credential_tail_note_when_table_matches_the_anchor_scheme(runner, wired):
    result = _invoke(
        runner, wired, "s3://bucket/sales/",
        "--table", "orders=s3://bucket/orders/**/*.parquet",
    )
    assert result.exit_code == 0, result.output
    assert "different object-store scheme" not in result.output


def test_serve_kind_override_reaches_resolution(runner, wired):
    _invoke(runner, wired, "s3://bucket/lake/", "--kind", "ducklake")
    target = wired["provision"]["target"]
    assert target.kind == "ducklake"
    assert target.data_path == "s3://bucket/lake/"
    assert target.init_sql == ""


def test_serve_rejects_an_unknown_kind(runner, wired):
    result = _invoke(runner, wired, "s3://bucket/lake/", "--kind", "sqlite")
    assert result.exit_code == 1
    assert "ducklake" in result.output
    assert "cmd" not in wired


def test_bare_serve_provisions_a_fresh_ducklake(runner, wired, tmp_path):
    _invoke(runner, wired)
    target = wired["provision"]["target"]
    assert target.kind == "ducklake"
    assert target.name == "main"


def test_provisioning_logs_in_and_ensures_everything(respx_mock, tmp_path):
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "jwt-1", "username": "admin"})
    )
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    respx_mock.post(f"{BASE}/api/tenant/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": []})
    )
    respx_mock.post(f"{BASE}/api/database/create").mock(return_value=httpx.Response(200, json={}))
    # First call is ensure_pool's own list-before-create check (must stay empty so
    # it creates); every call after is wait_node_routable's poll, which needs a
    # healthy node on the first one so the test does not really sleep out its
    # 60s default timeout.
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        side_effect=[
            httpx.Response(200, json={"pools": []}),
            httpx.Response(200, json={"pools": [
                {"tenant": "default", "tenantDb": "default_sales", "pool": "bi",
                 "nodes": [{"healthy": True}]}
            ]}),
        ]
    )
    respx_mock.post(f"{BASE}/api/pool/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(
            200, json={"flightSqlHost": "0.0.0.0", "flightSqlPort": 31338, "flightSqlTls": True}
        )
    )

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    target = resolve(str(f), data_root=tmp_path)
    lines = []
    # M6: a no-op sleep + a finite tick clock, so a future extra /api/pool/list
    # call (one more than the two-step side_effect above provides) raises
    # StopIteration immediately instead of really sleeping out the 60s default
    # node_timeout - see test_serve_provision.py's wait_node_routable tests
    # for why exactly 4 ticks matches this 2-call poll.
    ticks = iter([0.0, 1.0, 2.0, 3.0])
    ok = _provision(
        manager_url=BASE, tenant="default", target=target, pool="bi", size=1,
        password="pw", first_boot=True, profile="default", ready_timeout=5,
        pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
        node_timeout=10, node_sleep=lambda _s: None, node_now=lambda: next(ticks),
    )
    assert ok is True
    banner = "\n".join(lines)
    assert "jdbc:arrow-flight-sql://localhost:31338/" in banner
    assert "tenant=default" in banner and "pool=bi" in banner
    assert "pw" not in banner  # the password is never printed
    assert load_settings().token == "jwt-1"
    # Round 2 item 6: the seeded admin is a SUPERUSER row (tenant IS NULL), and the
    # login _provision just did was in that system realm - the saved profile must
    # say so, or a later `qod sql` authenticates in the tenant realm instead (where
    # no admin row exists) and fails the FlightSQL handshake with "Invalid password".
    assert load_settings().superuser is True


def test_provisioning_with_an_api_key_skips_login_and_keeps_the_profile_token(
    respx_mock, tmp_path
):
    from qod_cli.commands.serve import _provision
    from qod_cli.config import save_profile
    from qod_cli.serve_target import resolve

    save_profile("default", {"token": "old-jwt"})
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    target = resolve(str(f), data_root=tmp_path)
    login = respx_mock.post(f"{BASE}/api/auth/login")
    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    tenants = respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": [{"id": "default"}]})
    )
    # Every row already exists (ensure_database matches name, dataPath, kind and
    # initSql), so an unmocked create would fail the run.
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": [{
            "name": "default_sales", "dataPath": target.data_path, "kind": target.kind,
            "initSql": target.init_sql,
        }]})
    )
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        return_value=httpx.Response(200, json={"pools": [
            {"tenant": "default", "tenantDb": "default_sales", "pool": "bi",
             "nodes": [{"healthy": True}]}
        ]})
    )
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(200, json={"flightSqlPort": 31338})
    )
    ok = _provision(
        manager_url=BASE, tenant="default", target=target,
        pool="bi", size=1, profile="default", ready_timeout=5, pg_port=25432,
        pg_data_dir=str(tmp_path / "pg"), echo=lambda _l: None, api_key="k",
        node_timeout=10, node_sleep=lambda _s: None,
    )
    assert ok is True
    assert not login.called
    assert tenants.calls[0].request.headers["X-API-Key"] == "k"
    assert load_settings().token == "old-jwt"


def test_provisioning_reports_and_keeps_the_manager_on_failure(respx_mock, tmp_path):
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "jwt-1"})
    )
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(500, json={"error": "boom", "message": "database is down"})
    )
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    ok = _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", first_boot=True, profile="default",
        ready_timeout=5, pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
    )
    assert ok is False
    out = "\n".join(lines)
    assert "list tenants" in out
    assert "still running" in out
    assert "qod tenant list" in out


def test_banner_prints_all_three_protocols_with_real_values(respx_mock, tmp_path):
    # The manager's own boot box prints JDBC/ADBC/ODBC with <tenant>/<pool>/<user>
    # placeholders; serve's banner has the real provisioned values in hand and
    # should print all three protocols copy-paste ready, not just JDBC.
    import click

    from qod_cli.commands.serve import _banner

    banner = _banner(
        tenant="default", db="sales", pool="bi", size=1,
        first_boot=False, edge_host="edgehost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    )
    banner = unstyle(banner)
    # Connect lines are styled for readability (see the highlighting test below),
    # so match on the ANSI-stripped content rather than a raw prefix.
    plain = click.unstyle(banner)
    jdbc_line = next(line for line in plain.splitlines() if line.strip().startswith("JDBC"))
    adbc_line = next(line for line in plain.splitlines() if line.strip().startswith("ADBC"))
    odbc_line = next(line for line in plain.splitlines() if line.strip().startswith("ODBC"))

    # The seeded admin is a SUPERUSER row (tenant IS NULL); without a superuser
    # flag on the connect string the FlightSQL edge picks the TENANT auth realm
    # (tenant= is present), where no admin row exists, and login fails with
    # "Invalid password" for the exact string the banner just advertised.
    assert "user=admin" in jdbc_line
    assert "&superuser=true" in jdbc_line

    assert "grpc+tls://edgehost:31338" in adbc_line
    assert "tenant=default, pool=bi" in adbc_line
    assert "superuser=true" in adbc_line

    assert "TENANT=default;POOL=bi" in odbc_line
    assert "UID=admin" in odbc_line
    assert "PWD=<password>" in odbc_line
    assert "SUPERUSER=true" in odbc_line


def test_banner_highlights_connect_urls_and_password_for_readability(respx_mock, tmp_path):
    # The connect strings are what a user must copy off this screen; they should
    # visually stand out from the rest of the banner instead of blending into
    # ordinary scrollback text.
    import click

    from qod_cli.commands.serve import _banner

    banner = _banner(
        tenant="default", db="sales", pool="bi", size=1,
        first_boot=False, edge_host="edgehost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    )
    plain_to_raw = {click.unstyle(line): line for line in banner.splitlines()}

    for prefix in ("  JDBC ", "  ADBC ", "  ODBC ", "  UI   "):
        plain_line = next(p for p in plain_to_raw if p.startswith(prefix))
        assert plain_to_raw[plain_line] != plain_line, f"{prefix.strip()} line is not styled"

    assert "qod admin reset-password" in click.unstyle(banner)

    # Styling must be cosmetic only: the ANSI-stripped banner still reads exactly
    # like the plain content every other banner test asserts against.
    assert "jdbc:arrow-flight-sql://edgehost:31338/" in click.unstyle(banner)
    assert "qod auth change-password" in click.unstyle(banner)


def test_provisioning_never_raises_on_a_tokenless_login(respx_mock, tmp_path):
    # I-2: an uncaught KeyError on login["token"] would surface as a raw
    # traceback on the daemon thread, interleaved with the manager log.
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(return_value=httpx.Response(200, json={}))

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", first_boot=True, profile="default",
        ready_timeout=5, pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
    )
    out = "\n".join(lines)
    assert "provisioning failed unexpectedly" in out
    assert "still running" in out


def test_serve_demo_delegates_to_the_demo_runner(runner, wired, monkeypatch):
    from qod_cli.commands import serve as serve_cmd

    called = {}
    monkeypatch.setattr(
        serve_cmd, "run_demo", lambda ctx, version, jar: called.setdefault("args", (version, jar)),
        raising=False,
    )
    result = _invoke(runner, wired, "--demo")
    assert result.exit_code == 0, result.output
    assert "args" in called
    # The normal serve path must not run: no provisioning, no direct exec from serve.
    assert "provision" not in wired
    assert "cmd" not in wired


def test_serve_demo_refuses_a_target(runner, wired):
    result = _invoke(runner, wired, "./sales.duckdb", "--demo")
    assert result.exit_code == 1
    assert "--demo" in result.output and "TARGET" in result.output
    assert "cmd" not in wired


def test_serve_attaches_to_a_running_manager(runner, wired, respx_mock, tmp_path, monkeypatch):
    # B1: with a manager already answering /ready, serve must not boot a second
    # JVM - it attaches and provisions inline instead.
    from qod_cli.commands import serve as serve_cmd

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    monkeypatch.setenv("QOD_API_KEY", "k")
    captured = {}
    monkeypatch.setattr(serve_cmd, "_provision", lambda **kw: captured.update(kw) or True)

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert "cmd" not in wired
    assert "provision" not in wired  # _spawn_provisioning (the thread path) never runs
    assert captured["attached"] is True
    assert captured["api_key"] == "k"
    assert captured["manager_url"] == BASE
    assert captured["tenant"] == "default"
    assert captured["target"].name == "sales"


def test_serve_attach_uses_the_api_key_then_token_then_errors(
    runner, wired, respx_mock, tmp_path, monkeypatch
):
    from qod_cli.commands import serve as serve_cmd

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    captured = {}
    monkeypatch.setattr(serve_cmd, "_provision", lambda **kw: captured.update(kw) or True)
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")

    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert "no admin credential" in result.output
    assert "qod setup" not in result.output

    live = _jwt(exp=time.time() + 3600)
    monkeypatch.setenv("QOD_TOKEN", live)
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert captured["token"] == live

    monkeypatch.setenv("QOD_API_KEY", "k")
    _invoke(runner, wired, str(f))
    assert captured["api_key"] == "k"
    assert captured["token"] == ""


def _jwt(**claims) -> str:
    import base64
    import json

    def seg(obj):
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()

    return f"{seg({'alg': 'HS256'})}.{seg(claims)}.sig"


def test_serve_attach_treats_an_expired_token_as_absent_and_prompts(
    runner, wired, respx_mock, tmp_path, monkeypatch
):
    from qod_cli import admin_password as ap
    from qod_cli.commands import serve as serve_cmd
    from qod_cli.main import app

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    captured = {}
    monkeypatch.setattr(serve_cmd, "_provision", lambda **kw: captured.update(kw) or True)
    monkeypatch.setenv("QOD_TOKEN", _jwt(exp=time.time() - 60))
    monkeypatch.setattr(ap, "is_interactive", lambda: True)
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")

    result = runner.invoke(
        app, ["serve", str(f), "--jar", str(wired["jar"])], input="s3cret\n"
    )
    assert result.exit_code == 0, result.output
    assert captured["token"] == ""
    assert captured["password"] == "s3cret"
    assert captured["admin_names"] == ["admin@localhost.local", "admin"]


def test_serve_attach_expired_token_without_a_terminal_refuses(
    runner, wired, respx_mock, tmp_path, monkeypatch
):
    from qod_cli.commands import serve as serve_cmd

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    captured = {}
    monkeypatch.setattr(serve_cmd, "_provision", lambda **kw: captured.update(kw) or True)
    monkeypatch.setenv("QOD_TOKEN", _jwt(exp=time.time() - 60))
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")

    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert "no admin credential" in result.output
    assert captured == {}


def test_attach_provision_failure_tail_points_at_the_credentials(respx_mock, tmp_path):
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(return_value=httpx.Response(200, json={}))
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    ok = _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", profile="default", attached=True,
        ready_timeout=5, pg_port=0, pg_data_dir="", echo=lines.append,
    )
    out = "\n".join(lines)
    assert ok is False
    assert "the gateway is still running; fix the credentials (qod login, or export " in out
    assert "QOD_API_KEY) and re-run qod serve" in out
    assert "Ctrl-C" not in out


def test_banner_names_a_rotate_line_per_configured_admin(respx_mock, tmp_path):
    from qod_cli.commands.serve import _banner

    kw = dict(
        tenant="default", db="sales", pool="bi", size=1, first_boot=False,
        edge_host="localhost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    )
    banner = unstyle(_banner(**kw))
    assert "qod auth change-password --username admin@localhost.local" in banner
    assert "qod auth change-password --username admin\n" in banner
    banner = unstyle(_banner(**kw, admin_names=["ops", "root"]))
    assert "qod auth change-password --username ops" in banner
    assert "qod auth change-password --username root" in banner
    assert "--username admin" not in banner


def test_serve_refuses_to_attach_to_a_non_loopback_manager(
    runner, wired, respx_mock, tmp_path, monkeypatch
):
    # M7: a manager already running at a REMOTE (non-loopback) URL is almost
    # certainly a `qod login` profile against someone else's deployment;
    # attaching would quietly create rows there with a local dataPath the
    # remote nodes cannot read. Refuse outright - no attach, no local boot.
    from qod_cli.commands import serve as serve_cmd

    remote = "http://example.com:20900"
    monkeypatch.setenv("QOD_MANAGER_URL", remote)
    respx_mock.get(f"{remote}/ready").mock(return_value=httpx.Response(200, json={}))
    monkeypatch.setenv("QOD_API_KEY", "k")
    called = []
    monkeypatch.setattr(serve_cmd, "_provision", lambda **kw: called.append(kw) or True)

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 1
    assert remote in result.output
    assert "loopback" in result.output
    assert called == []
    assert "cmd" not in wired


def test_serve_demo_refuses_when_a_manager_is_already_running(
    runner, wired, respx_mock, monkeypatch
):
    from qod_cli.commands import serve as serve_cmd

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    called = []
    monkeypatch.setattr(
        serve_cmd, "run_demo", lambda *a, **kw: called.append(1), raising=False
    )
    result = _invoke(runner, wired, "--demo")
    assert result.exit_code == 1
    assert "qod stop" in result.output
    assert called == []


def test_serve_boots_normally_when_the_ready_probe_errors(runner, wired, respx_mock, tmp_path):
    # Guards the probe's failure posture: any exception from the GET (connection
    # refused, timeout, ...) must mean "nothing is running" and fall through to
    # the ordinary boot path, not abort serve.
    respx_mock.get(f"{BASE}/ready").mock(side_effect=httpx.ConnectError("refused"))
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert "cmd" in wired


def test_manager_running_probe_uses_a_per_phase_bounded_timeout(monkeypatch):
    # M14: the docstring promises a bounded probe; pin that httpx.Timeout(2.0,
    # connect=1.0) - not a bare float, and not left to httpx's own default -
    # is what actually reaches httpx.get.
    from qod_cli.commands import serve as serve_cmd

    captured = {}

    def fake_get(url, timeout=None):
        captured["url"] = url
        captured["timeout"] = timeout
        return httpx.Response(200, json={})

    monkeypatch.setattr(serve_cmd.httpx, "get", fake_get)
    assert serve_cmd._manager_running(BASE) is True
    assert captured["url"] == f"{BASE}/ready"
    timeout = captured["timeout"]
    assert isinstance(timeout, httpx.Timeout)
    assert timeout.connect == 1.0
    assert timeout.read == timeout.write == timeout.pool == 2.0


def test_banner_first_boot_says_the_password_is_not_stored(respx_mock, tmp_path):
    from qod_cli.commands.serve import _banner

    banner = unstyle(_banner(
        tenant="default", db="sales", pool="bi", size=1, first_boot=True,
        edge_host="localhost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
    ))
    assert "password      : the one you just set (not stored anywhere)" in banner
    assert "qod admin reset-password" not in banner
    assert "QOD_ADMIN_PASSWORD" not in banner


def test_banner_attached_render(respx_mock, tmp_path):
    from qod_cli.commands.serve import _banner

    banner = _banner(
        tenant="default", db="sales", pool="bi", size=1, first_boot=False,
        edge_host="localhost", edge_port=31338, manager_url=BASE,
        pg_port=25432, pg_data_dir="/x/pg", description="DuckDB file /abs/sales.duckdb",
        attached=True,
    )
    banner = unstyle(banner)
    assert "provisioned into the running gateway" in banner
    assert BASE in banner
    assert "gateway already running; stop it with: qod stop" in banner
    assert "embedded postgres" not in banner
    assert "pg data" not in banner
    assert "Ctrl-C" not in banner


@pytest.fixture
def starflow_wired(wired, monkeypatch):
    from qod_cli import starflow
    from qod_cli.commands import _starflow

    tmp = wired["tmp"]
    monkeypatch.setattr(starflow, "java_home_of", lambda java, run=None: "/jdk")
    monkeypatch.setattr(starflow, "ensure_installed", lambda *a, **kw: None)
    monkeypatch.setattr(_starflow, "_user_home", lambda: tmp / "home")
    monkeypatch.setattr(_starflow, "_spawn", lambda **kw: wired.setdefault("starflow", kw))
    monkeypatch.delenv("SL_URL", raising=False)
    monkeypatch.delenv("SL_API_HTTP_PORT", raising=False)
    monkeypatch.delenv("STARLAKE_HOME", raising=False)
    monkeypatch.delenv("STARFLOW_ENV_FILE", raising=False)
    monkeypatch.delenv("STARFLOW_VERSION", raising=False)
    # Pin the platform so these tests exercise the wiring on every OS.
    monkeypatch.setattr(_starflow, "_is_windows", lambda: False)
    return wired


def test_serve_with_starflow_uses_the_embedded_postgres(runner, starflow_wired):
    result = _invoke(runner, starflow_wired, "--with-starflow", "--pg-port", "25999")
    assert result.exit_code == 0, result.output
    mgr = starflow_wired["env"]
    assert mgr["SL_ENABLED"] == "true"
    assert mgr["SL_URL"] == "http://localhost:9900"
    assert mgr["QOD_API_KEY"] and mgr["QOD_SESSION_JWT_SECRET"] == mgr["JWT_SECRET_KEY"]
    sf = starflow_wired["starflow"]
    assert sf["pg"].port == 25999
    assert (sf["pg"].user, sf["pg"].password) == ("postgres", "postgres")
    assert sf["env"]["SL_API_JDBC_URL"] == "jdbc:postgresql://localhost:25999/starlake"
    assert sf["env"]["QOD_API_KEY"] == mgr["QOD_API_KEY"]
    assert "provision" in starflow_wired  # serve's own provisioning still runs


def test_serve_demo_refuses_with_starflow(runner, starflow_wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["serve", "--demo", "--with-starflow"])
    assert result.exit_code == 1
    assert "cannot run with the demo" in result.output
    assert "drop --demo" in result.output


def test_serve_attach_mode_refuses_with_starflow(runner, starflow_wired, monkeypatch):
    from qod_cli.commands import serve as serve_cmd

    monkeypatch.setattr(serve_cmd, "_manager_running", lambda url: True)
    result = _invoke(runner, starflow_wired, "--with-starflow")
    assert result.exit_code == 1
    assert "stop it first" in result.output
    assert "starflow" not in starflow_wired


def test_serve_starflow_flag_without_with_starflow_is_a_usage_error(runner, wired):
    result = _invoke(runner, wired, "--starflow-url", "http://x:1")
    assert result.exit_code == 2
    assert "--with-starflow" in result.output


def test_provisioning_substitutes_a_null_flight_sql_host(respx_mock, tmp_path):
    # I-2: a JSON-null flightSqlHost must take the same substitution branch as
    # "" and "0.0.0.0", not land None in the JDBC connection string.
    from qod_cli.commands.serve import _provision
    from qod_cli.serve_target import resolve

    respx_mock.get(f"{BASE}/ready").mock(return_value=httpx.Response(200, json={}))
    respx_mock.post(f"{BASE}/api/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "jwt-1"})
    )
    respx_mock.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    respx_mock.post(f"{BASE}/api/tenant/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/database/list").mock(
        return_value=httpx.Response(200, json={"tenantDbs": []})
    )
    respx_mock.post(f"{BASE}/api/database/create").mock(return_value=httpx.Response(200, json={}))
    # See test_provisioning_logs_in_and_ensures_everything for why this is a
    # two-step side_effect rather than a single return_value.
    respx_mock.get(f"{BASE}/api/pool/list").mock(
        side_effect=[
            httpx.Response(200, json={"pools": []}),
            httpx.Response(200, json={"pools": [
                {"tenant": "default", "tenantDb": "default_sales", "pool": "bi",
                 "nodes": [{"healthy": True}]}
            ]}),
        ]
    )
    respx_mock.post(f"{BASE}/api/pool/create").mock(return_value=httpx.Response(200, json={}))
    respx_mock.get(f"{BASE}/api/config/client").mock(
        return_value=httpx.Response(
            200, json={"flightSqlHost": None, "flightSqlPort": 31338, "flightSqlTls": True}
        )
    )

    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    lines = []
    # See test_provisioning_logs_in_and_ensures_everything for why this is a
    # no-op sleep + finite tick clock rather than the real ones.
    ticks = iter([0.0, 1.0, 2.0, 3.0])
    _provision(
        manager_url=BASE, tenant="default", target=resolve(str(f), data_root=tmp_path),
        pool="bi", size=1, password="pw", first_boot=True, profile="default",
        ready_timeout=5, pg_port=25432, pg_data_dir=str(tmp_path / "pg"), echo=lines.append,
        node_timeout=10, node_sleep=lambda _s: None, node_now=lambda: next(ticks),
    )
    banner = "\n".join(lines)
    assert "jdbc:arrow-flight-sql://localhost:31338/" in banner
    assert "None" not in banner


def test_serve_migrates_a_stored_admin_password_out_of_the_config(runner, wired, tmp_path):
    from qod_cli.config import save_start_env

    save_start_env({"QOD_ADMIN_PASSWORD": "old-stored"})
    f = tmp_path / "sales.duckdb"
    f.write_bytes(b"")
    result = _invoke(runner, wired, str(f))
    assert result.exit_code == 0, result.output
    assert "Your current admin password is: old-stored" in result.output
    assert "QOD_ADMIN_PASSWORD" not in load_start_env()
    assert "QOD_ADMIN_PASSWORD" not in wired["env"]
