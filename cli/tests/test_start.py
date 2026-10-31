import pathlib
import sys

import pytest
import respx

from qod_cli import launcher
from tests import _duckdb_cache

_REAL_ENSURE_CLI, _REAL_ENSURE_LIB = launcher.ensure_duckdb_cli, launcher.ensure_libduckdb


@pytest.fixture
def wired(monkeypatch, tmp_path):
    """Stub every provisioning step and capture the exec; returns the capture dict."""
    from qod_cli.commands import start as start_cmd

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
        launcher,
        "materialize_spawn_scripts",
        lambda dest: (tmp_path / "s.sh", tmp_path / "s.ps1"),
    )
    monkeypatch.setattr(launcher, "default_data_dir", lambda: tmp_path / "state")
    monkeypatch.setattr(
        launcher,
        "materialize_loader_scripts",
        lambda dest: {n: tmp_path / n for n in launcher.LOADER_SCRIPTS},
    )
    monkeypatch.setattr(start_cmd.shutil, "which", lambda name: None)

    from qod_cli import admin_password as ap

    captured["admin_state"] = ap.PRESENT
    monkeypatch.setattr(
        ap, "control_plane_admin_state", lambda pg, names, connect=None: captured["admin_state"]
    )
    monkeypatch.setattr(ap, "is_interactive", lambda: False)

    def fake_exec(cmd, env=None):
        captured["cmd"], captured["env"] = cmd, env

    monkeypatch.setattr(start_cmd, "_exec", fake_exec)
    monkeypatch.setattr(start_cmd.os, "chdir", lambda d: captured.setdefault("cwd", d))
    captured["jar"] = jar
    return captured


def test_start_launches_manager_without_demo_arg(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["cmd"][:4] == [
        "/usr/bin/java",
        "-Darrow.allocation.manager.type=Unsafe",
        "-jar",
        str(wired["jar"]),
    ]
    assert "demo" not in wired["cmd"]


def test_start_passes_extra_args(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"]), "--whatever"])
    assert result.exit_code == 0, result.output
    assert wired["cmd"][-1] == "--whatever"


def test_start_defaults_ducklake_data_path_and_cwd(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    state = wired["env"]["QOD_DUCKLAKE_DATA_PATH"]
    assert state.endswith("ducklake/data") or state.endswith("ducklake\\data")
    assert pathlib.Path(wired["cwd"]).name == "state"


def test_start_respects_existing_data_path(runner, wired, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setenv("QOD_DUCKLAKE_DATA_PATH", "/mnt/lake")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_DUCKLAKE_DATA_PATH"] == "/mnt/lake"


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="Windows resolves the native duckdb DLL through PATH, not DYLD/LD_LIBRARY_PATH "
    "(runtime_env's os_name-to-var mapping has no 'win32' entry)",
)
def test_start_wires_native_client_library_path(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    libvar = wired["env"].get("DYLD_LIBRARY_PATH") or wired["env"].get("LD_LIBRARY_PATH")
    assert libvar and str(pathlib.Path(launcher.DUCKDB_CLI_VERSION) / "lib") in libvar


def test_start_hands_the_jvm_a_preloadable_libduckdb(runner, wired, monkeypatch, tmp_path):
    # The real ensure_* over a provisioned cache: DUCKDB_CACHE_DIR must name it, since a
    # SIP-protected /usr/bin/java never sees DYLD_LIBRARY_PATH.
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "ensure_duckdb_cli", _REAL_ENSURE_CLI)
    monkeypatch.setattr(launcher, "ensure_libduckdb", _REAL_ENSURE_LIB)
    monkeypatch.setattr(launcher, "default_cache_dir", lambda: tmp_path / "cache")
    _, lib_dir = _duckdb_cache.plant(tmp_path / "cache")
    with respx.mock:  # no routes: a provisioned cache must not hit the network
        result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    target = _duckdb_cache.preload_target(wired["env"])
    assert target.is_file() and target.parent == lib_dir


def test_start_downloads_jvm_when_missing(runner, wired, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "find_java", lambda: None)
    monkeypatch.setattr(launcher, "is_musl", lambda root=None: False)
    monkeypatch.setattr(launcher, "ensure_jre", lambda cache_dir=None: "/cache/jre/bin/java")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["cmd"][0] == "/cache/jre/bin/java"


def test_start_refuses_pre_launcher_releases(runner, wired, monkeypatch):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--version", "0.3.7"])
    assert result.exit_code == 1
    assert "0.3.8" in result.output


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="loaders are bash scripts; _spawn_loaders returns early on win32 without "
    "spawning or setting QOD_BOOTSTRAP_YAML (see start.py::_spawn_loaders), matching "
    "the dedicated test_start_load_flags_warn_and_skip_on_windows behavior",
)
def test_start_load_tpch_spawns_loader_and_sets_bootstrap(runner, wired, monkeypatch, tmp_path):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    spawned = []
    monkeypatch.setattr(
        start_cmd.subprocess, "Popen", lambda cmd, **kw: spawned.append((cmd, kw)) or None
    )
    monkeypatch.setattr(
        launcher,
        "materialize_loader_scripts",
        lambda dest: {n: tmp_path / n for n in launcher.LOADER_SCRIPTS},
    )
    monkeypatch.setenv("LOAD_TPCH", "1")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_BOOTSTRAP_YAML"] == "classpath:bootstrap-demo.yaml"
    assert len(spawned) == 1
    cmd, kw = spawned[0]
    assert cmd[-1].endswith("load-tpch-dbgen.sh")
    loader_env = kw["env"]
    assert loader_env["SF"] == "1"
    assert loader_env["DB_NAME"] == "acme_tpch"
    assert loader_env["SCHEMA_NAME"] == "tpch1"
    assert loader_env["DATA_PATH"].endswith("acme_tpch")


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="loaders are bash scripts; _spawn_loaders returns early on win32 without "
    "spawning or setting QOD_BOOTSTRAP_YAML (see start.py::_spawn_loaders)",
)
def test_start_demo_minimal_picks_minimal_manifest(runner, wired, monkeypatch, tmp_path):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    monkeypatch.setattr(start_cmd.subprocess, "Popen", lambda cmd, **kw: None)
    monkeypatch.setattr(
        launcher,
        "materialize_loader_scripts",
        lambda dest: {n: tmp_path / n for n in launcher.LOADER_SCRIPTS},
    )
    monkeypatch.setenv("LOAD_TPCH", "1")
    monkeypatch.setenv("DEMO", "minimal")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_BOOTSTRAP_YAML"] == "classpath:bootstrap-demo-minimal.yaml"


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="loaders are bash scripts; _spawn_loaders returns early on win32 without "
    "spawning any of them (see start.py::_spawn_loaders)",
)
def test_start_load_tpc_legacy_spawns_all_three(runner, wired, monkeypatch, tmp_path):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    spawned = []
    monkeypatch.setattr(
        start_cmd.subprocess, "Popen", lambda cmd, **kw: spawned.append((cmd, kw)) or None
    )
    monkeypatch.setattr(
        launcher,
        "materialize_loader_scripts",
        lambda dest: {n: tmp_path / n for n in launcher.LOADER_SCRIPTS},
    )
    monkeypatch.setenv("LOAD_TPC", "2")
    monkeypatch.setenv("LOAD_SSB", "5")  # explicit var wins over the shortcut
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    by_script = {c[-1].rsplit("load-", 1)[-1]: k["env"]["SF"] for c, k in spawned}
    assert by_script == {"tpch-dbgen.sh": "2", "tpcds-dbgen.sh": "2", "ssb-dbgen.sh": "5"}


def test_start_respects_existing_bootstrap_yaml(runner, wired, monkeypatch, tmp_path):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    monkeypatch.setattr(start_cmd.subprocess, "Popen", lambda cmd, **kw: None)
    monkeypatch.setattr(
        launcher,
        "materialize_loader_scripts",
        lambda dest: {n: tmp_path / n for n in launcher.LOADER_SCRIPTS},
    )
    monkeypatch.setenv("LOAD_TPCH", "1")
    monkeypatch.setenv("QOD_BOOTSTRAP_YAML", "/my/manifest.yaml")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_BOOTSTRAP_YAML"] == "/my/manifest.yaml"


def test_start_nuke_wipes_state_dirs(runner, wired, monkeypatch, tmp_path):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    state = tmp_path / "state"
    for d in ("ducklake", "state", "certs"):
        (state / d).mkdir(parents=True)
        (state / d / "junk").write_text("x")
    monkeypatch.setattr(start_cmd.shutil, "which", lambda name: None)  # no psql
    monkeypatch.setenv("NUKE", "1")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert not (state / "ducklake").exists()
    assert not (state / "certs").exists()


def test_start_nuke_drops_databases_via_psql(runner, wired, monkeypatch):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    calls = []
    monkeypatch.setattr(start_cmd.shutil, "which", lambda name: "/usr/bin/psql")
    monkeypatch.setattr(
        start_cmd.subprocess,
        "run",
        lambda cmd, **kw: calls.append(cmd) or type("R", (), {"returncode": 0, "stdout": ""})(),
    )
    monkeypatch.setenv("NUKE", "1")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    sql = " ".join(" ".join(c) for c in calls)
    assert 'DROP DATABASE IF EXISTS "qod"' in sql
    assert 'DROP DATABASE IF EXISTS "acme_tpch"' in sql


def test_bundled_loader_scripts_match_the_canonical_ones():
    repo_scripts = pathlib.Path(__file__).resolve().parents[2] / "scripts"
    for name in launcher.LOADER_SCRIPTS:
        bundled = launcher.bundled_spawn_script(name)
        assert bundled.read_bytes() == (repo_scripts / name).read_bytes(), (
            f"{name} drifted: re-copy scripts/{name} into cli/src/qod_cli/scripts/"
        )


def test_start_resolves_relative_jar_before_chdir(runner, wired, monkeypatch, tmp_path):
    from qod_cli.main import app

    monkeypatch.chdir(wired["jar"].parent)
    result = runner.invoke(app, ["start", "--jar", wired["jar"].name])
    assert result.exit_code == 0, result.output
    jar_arg = wired["cmd"][wired["cmd"].index("-jar") + 1]
    assert pathlib.Path(jar_arg).is_absolute()


def test_start_applies_setup_config(runner, wired, monkeypatch):
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    save_start_env({"QOD_PG_HOST": "db.internal", "QOD_PG_PASSWORD": "s3cret"})
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_PG_HOST"] == "db.internal"
    assert wired["env"]["QOD_PG_PASSWORD"] == "s3cret"


def test_start_real_env_var_wins_over_setup_config(runner, wired, monkeypatch):
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    save_start_env({"QOD_PG_HOST": "from-setup"})
    monkeypatch.setenv("QOD_PG_HOST", "from-shell")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_PG_HOST"] == "from-shell"


@pytest.mark.parametrize("demo", [False, True])
def test_start_warns_on_a_static_api_key_in_the_shell_and_launches(runner, wired, monkeypatch, demo):
    # `qod start --demo` (the demo's deprecated alias) goes through the same check.
    import qod_cli.commands.demo as demo_mod
    from qod_cli.main import app

    ran = []
    monkeypatch.setattr(demo_mod, "run_demo", lambda *a, **kw: ran.append(1))
    monkeypatch.setenv("QOD_API_KEY", "stale-key-value")
    args = ["start", "--jar", str(wired["jar"])] + (["--demo"] if demo else [])
    result = runner.invoke(app, args)
    assert result.exit_code == 0, result.output
    assert "warning: QOD_API_KEY is set" in result.output
    assert "your shell: run `unset QOD_API_KEY`" in result.output
    assert "stale-key-value" not in result.output
    if demo:
        assert ran == [1]
    else:
        # Booleans only: a failing assertion must never print an environment.
        key_in_manager_env = "QOD_API_KEY" in wired["env"]
        assert not key_in_manager_env


def test_start_warns_on_a_static_api_key_in_the_start_table(runner, wired):
    from qod_cli.config import config_path, save_start_env
    from qod_cli.main import app

    save_start_env({"QOD_API_KEY": "stale-key-value"})
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert (
        f"the [start] table of {config_path()}: delete its QOD_API_KEY line, or run "
        "qod setup --unset QOD_API_KEY --non-interactive"
    ) in result.output
    assert "your shell" not in result.output
    assert "stale-key-value" not in result.output
    key_in_manager_env = "QOD_API_KEY" in wired["env"]
    assert not key_in_manager_env


def test_start_names_every_source_of_a_static_api_key(runner, wired, monkeypatch):
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    monkeypatch.setenv("QOD_API_KEY", "stale-key-value")
    save_start_env({"QOD_API_KEY": "stale-key-value"})
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "your shell" in result.output
    assert "the [start] table of" in result.output


def test_start_accepts_an_empty_static_api_key(runner, wired, monkeypatch):
    # An empty value passes silently (older Compose files render API_KEY= as one).
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    monkeypatch.setenv("QOD_API_KEY", "  ")
    save_start_env({"QOD_API_KEY": ""})
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "cmd" in wired
    assert "QOD_API_KEY" not in result.output


def test_start_load_flags_warn_and_skip_on_windows(runner, wired, monkeypatch, tmp_path):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    spawned = []
    monkeypatch.setattr(
        start_cmd.subprocess, "Popen", lambda cmd, **kw: spawned.append(cmd) or None
    )
    monkeypatch.setattr(start_cmd.sys, "platform", "win32")
    monkeypatch.setenv("LOAD_TPCH", "1")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert spawned == []
    assert "Windows" in result.output


def test_nuke_confirmation_aborts_on_wrong_answer(monkeypatch, capsys):
    import typer
    import pytest as _pytest

    from qod_cli.commands import start as start_mod

    monkeypatch.setattr(start_mod.sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(start_mod.typer, "prompt", lambda *a, **k: "wrong")
    destroyed = []
    monkeypatch.setattr(start_mod, "_psql", lambda *a, **k: destroyed.append(a))
    with _pytest.raises(typer.Exit) as exc:
        start_mod._nuke(start_mod.Path("/nonexistent"), {"dbname": "qod"})
    assert exc.value.exit_code == 1
    assert destroyed == []  # nothing dropped before the abort
    assert "aborted" in capsys.readouterr().err


def test_start_demo_still_works_and_points_at_serve(runner, wired, monkeypatch):
    import qod_cli.commands.demo as demo_mod

    called = {}
    monkeypatch.setattr(demo_mod, "run_demo", lambda ctx, version, jar: called.setdefault("hit", True))
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--demo", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert called.get("hit") is True
    assert "qod serve --demo" in result.output  # the deprecation pointer


def test_start_echoes_a_staleness_hint_when_present(runner, wired, monkeypatch):
    monkeypatch.setattr(launcher, "newer_release_hint", lambda current: f"note: newer than {current}")

    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "note: newer than" in result.output


def test_start_stays_silent_without_a_staleness_hint(runner, wired, monkeypatch):
    monkeypatch.setattr(launcher, "newer_release_hint", lambda current: None)

    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "note:" not in result.output


def test_nuke_confirmation_skips_for_non_tty(monkeypatch, tmp_path):
    from qod_cli.commands import start as start_mod

    calls = []
    monkeypatch.setattr(start_mod, "_psql", lambda *a, **k: calls.append(a))
    monkeypatch.setattr(start_mod.shutil, "which", lambda n: "/usr/bin/psql")
    monkeypatch.setattr(start_mod.sys.stdin, "isatty", lambda: False)
    start_mod._nuke(tmp_path, {"dbname": "qod", "password": ""})
    assert calls  # drops proceeded without any prompt


@pytest.fixture
def starflow_wired(wired, monkeypatch, tmp_path):
    """--with-starflow on top of `wired`: no install, no thread, no real secrets."""
    from qod_cli import starflow
    from qod_cli.commands import _starflow

    monkeypatch.setattr(starflow, "java_home_of", lambda java, run=None: "/jdk")
    monkeypatch.setattr(starflow, "ensure_installed", lambda *a, **kw: None)
    monkeypatch.setattr(launcher, "default_cache_dir", lambda: tmp_path / "cache")
    monkeypatch.setattr(_starflow, "_user_home", lambda: tmp_path / "home")
    monkeypatch.setattr(_starflow, "_spawn", lambda **kw: wired.setdefault("starflow", kw))
    monkeypatch.delenv("SL_URL", raising=False)
    monkeypatch.delenv("SL_API_HTTP_PORT", raising=False)
    monkeypatch.delenv("STARLAKE_HOME", raising=False)
    monkeypatch.delenv("STARFLOW_ENV_FILE", raising=False)
    monkeypatch.delenv("STARFLOW_VERSION", raising=False)
    # Pin the platform so these tests exercise the wiring on every OS.
    monkeypatch.setattr(_starflow, "_is_windows", lambda: False)
    # Never probe a real :20900 (a user-started manager would refuse every test).
    from qod_cli.commands import start as start_cmd

    monkeypatch.setattr(start_cmd, "_manager_running", lambda url: False)
    return wired


def test_start_with_starflow_wires_both_sides(runner, starflow_wired, monkeypatch):
    from qod_cli.config import load_start_env
    from qod_cli.main import app

    monkeypatch.setenv("QOD_PG_PORT", "5433")
    result = runner.invoke(
        app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow", "--starflow-port", "9000"]
    )
    assert result.exit_code == 0, result.output
    mgr = starflow_wired["env"]
    stored = load_start_env()
    assert mgr["SL_ENABLED"] == "true"
    assert mgr["SL_URL"] == "http://localhost:9000"
    assert mgr["QOD_SESSION_JWT_SECRET"] == stored["QOD_SESSION_JWT_SECRET"]
    assert mgr["JWT_SECRET_KEY"] == stored["QOD_SESSION_JWT_SECRET"]
    sf = starflow_wired["starflow"]
    # Booleans first: a failing assertion must never print an environment.
    key_in = {
        "manager": "QOD_API_KEY" in mgr,
        "starflow": "QOD_API_KEY" in sf["env"],
        "[start]": "QOD_API_KEY" in stored,
    }
    assert key_in == {"manager": False, "starflow": False, "[start]": False}
    assert sf["env"]["JWT_SECRET_KEY"] == mgr["QOD_SESSION_JWT_SECRET"]
    assert sf["env"]["SL_API_JDBC_URL"] == "jdbc:postgresql://localhost:5433/starlake"
    assert sf["env"]["SL_API_JDBC_PASSWORD"] == "azizam"
    assert sf["pg"].port == 5433
    assert sf["url"] == "http://localhost:9000"
    assert sf["home"].name == "starflow"


def test_start_with_starflow_env_file_and_remote_url(runner, starflow_wired, tmp_path):
    from qod_cli.main import app

    env_file = tmp_path / "local-qod.env"
    env_file.write_text("SL_API_HTTP_PORT=9000\nSL_API_MODE=ALL\nQOD_API_KEY=stale\n")
    result = runner.invoke(
        app,
        [
            "start", "--jar", str(starflow_wired["jar"]), "--with-starflow",
            "--starflow-env-file", str(env_file), "--starflow-url", "https://sf.example.com",
        ],
    )
    assert result.exit_code == 0, result.output
    sf_env = starflow_wired["starflow"]["env"]
    assert sf_env["SL_API_HTTP_PORT"] == "9000"
    assert sf_env["SL_API_MODE"] == "ALL"
    key_in_starflow = "QOD_API_KEY" in sf_env
    assert not key_in_starflow  # the env file's stale key never reaches Starflow
    assert sf_env["SL_API_DOMAIN"] == "sf.example.com"
    assert starflow_wired["env"]["SL_URL"] == "https://sf.example.com"


def test_start_with_starflow_reuses_the_persisted_secret(runner, starflow_wired):
    from qod_cli.config import load_start_env, save_start_env
    from qod_cli.main import app

    save_start_env({"QOD_SESSION_JWT_SECRET": "s1"})
    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 0, result.output
    assert starflow_wired["env"]["JWT_SECRET_KEY"] == "s1"
    assert starflow_wired["starflow"]["env"]["JWT_SECRET_KEY"] == "s1"
    # Key names only: the stored values never reach an assertion message.
    assert sorted(load_start_env()) == ["QOD_SESSION_JWT_SECRET"]


def test_start_starflow_flag_without_with_starflow_is_a_usage_error(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"]), "--starflow-port", "9000"])
    assert result.exit_code == 2
    assert "--with-starflow" in result.output
    assert "cmd" not in wired


def test_start_without_starflow_injects_nothing(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "SL_ENABLED" not in wired["env"]


def test_start_demo_refuses_with_starflow(runner, starflow_wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--demo", "--with-starflow"])
    assert result.exit_code == 1
    assert "cannot run with the demo" in result.output


def test_start_with_starflow_refuses_when_a_manager_is_already_running(
    runner, starflow_wired, monkeypatch
):
    # qod cannot know, or inject, the running manager's session secret, so the
    # pair could never authenticate.
    from qod_cli import starflow
    from qod_cli.commands import start as start_cmd
    from qod_cli.config import load_start_env
    from qod_cli.main import app

    monkeypatch.setenv("QOD_ON_DEMAND_PORT", "20911")
    probed = []
    monkeypatch.setattr(start_cmd, "_manager_running", lambda url: probed.append(url) or True)
    monkeypatch.setattr(
        starflow, "stop_running", lambda *a, **kw: pytest.fail("must not reap anything")
    )
    monkeypatch.delenv("QOD_SESSION_JWT_SECRET", raising=False)
    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 1
    assert probed == ["http://localhost:20911"]
    assert (
        "error: a manager is already running at http://localhost:20911; --with-starflow "
        "needs to launch it itself: stop it first (qod stop)"
    ) in result.output
    assert "cmd" not in starflow_wired
    assert "starflow" not in starflow_wired
    assert sorted(load_start_env()) == []


def test_start_with_starflow_probe_reads_the_port_from_the_stored_config(
    runner, starflow_wired, monkeypatch
):
    from qod_cli.commands import start as start_cmd
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    monkeypatch.delenv("QOD_ON_DEMAND_PORT", raising=False)
    save_start_env({"QOD_ON_DEMAND_PORT": "20922"})
    probed = []
    monkeypatch.setattr(start_cmd, "_manager_running", lambda url: probed.append(url) or False)
    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 0, result.output
    assert probed == ["http://localhost:20922"]
    assert "cmd" in starflow_wired


def test_start_without_starflow_never_probes_for_a_running_manager(runner, wired, monkeypatch):
    from qod_cli.commands import start as start_cmd
    from qod_cli.main import app

    monkeypatch.setattr(
        start_cmd, "_manager_running", lambda url: pytest.fail("no probe without --with-starflow")
    )
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "cmd" in wired


def test_start_probe_delegates_to_serve(monkeypatch):
    from qod_cli.commands import serve as serve_cmd
    from qod_cli.commands import start as start_cmd

    seen = []
    monkeypatch.setattr(serve_cmd, "_manager_running", lambda url: seen.append(url) or True)
    assert start_cmd._manager_running("http://localhost:1") is True
    assert seen == ["http://localhost:1"]


def test_start_with_starflow_refused_on_windows(runner, wired, monkeypatch):
    from qod_cli.commands import _starflow
    from qod_cli.main import app

    monkeypatch.setattr(_starflow, "_is_windows", lambda: True)
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"]), "--with-starflow"])
    assert result.exit_code == 1
    assert "Windows" in result.output


def test_start_with_starflow_reaps_a_leftover_starflow_before_installing(
    runner, starflow_wired, monkeypatch, tmp_path
):
    # The manager died on its own and left Starflow running: the next start must
    # stop it before its pid file is overwritten (and the process lost track of).
    from qod_cli import starflow
    from qod_cli.main import app

    state = launcher.default_data_dir()
    state.mkdir(parents=True, exist_ok=True)
    (state / starflow.PID_FILE).write_text("4242\n/opt/sf\n")
    order = []
    monkeypatch.setattr(
        starflow, "stop_running", lambda state_dir, echo: order.append(("stop", state_dir))
    )
    monkeypatch.setattr(starflow, "ensure_installed", lambda *a, **kw: order.append("install"))
    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 0, result.output
    assert order == [("stop", state), "install"]


def test_start_with_starflow_leftover_stop_failure_is_a_warning(
    runner, starflow_wired, monkeypatch
):
    from qod_cli import starflow
    from qod_cli.main import app

    def boom(state_dir, echo):
        raise RuntimeError("unreadable pid file")

    monkeypatch.setattr(starflow, "stop_running", boom)
    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 0, result.output
    assert "WARN" in result.output and "unreadable pid file" in result.output
    assert "starflow" in starflow_wired


def test_start_with_starflow_says_it_generated_the_session_secret(runner, starflow_wired):
    from qod_cli.config import config_path, load_start_env
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 0, result.output
    assert (
        "generated QOD_SESSION_JWT_SECRET for the Starflow pairing "
        f"(stored in {config_path()})"
    ) in result.output
    assert "QOD_API_KEY" not in result.output
    secret_printed = load_start_env()["QOD_SESSION_JWT_SECRET"] in result.output
    assert not secret_printed


def test_start_with_starflow_silent_when_the_secret_exists(runner, starflow_wired):
    from qod_cli.config import save_start_env
    from qod_cli.main import app

    save_start_env({"QOD_SESSION_JWT_SECRET": "s1"})
    result = runner.invoke(app, ["start", "--jar", str(starflow_wired["jar"]), "--with-starflow"])
    assert result.exit_code == 0, result.output
    assert "generated" not in result.output


def test_start_first_boot_uses_the_exported_password(runner, wired, monkeypatch):
    from qod_cli.main import app

    wired["admin_state"] = "absent"
    monkeypatch.setenv("QOD_ADMIN_PASSWORD", "from-shell")
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_ADMIN_PASSWORD"] == "from-shell"


def test_start_first_boot_prompts_on_a_terminal(runner, wired, monkeypatch):
    from qod_cli import admin_password as ap
    from qod_cli.main import app

    wired["admin_state"] = "absent"
    monkeypatch.setattr(ap, "is_interactive", lambda: True)
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])], input="s3cret\ns3cret\n")
    assert result.exit_code == 0, result.output
    assert wired["env"]["QOD_ADMIN_PASSWORD"] == "s3cret"
    cfg = pathlib.Path(wired["jar"]).parent / "config.toml"
    assert not cfg.exists() or "s3cret" not in cfg.read_text()


def test_start_first_boot_refuses_without_terminal_or_env(runner, wired):
    from qod_cli.main import app

    wired["admin_state"] = "absent"
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 1
    assert "first boot needs an admin password" in result.output
    assert "cmd" not in wired


def test_start_later_boot_never_prompts_and_passes_nothing(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "QOD_ADMIN_PASSWORD" not in wired["env"]


def test_start_unreachable_postgres_does_not_prompt(runner, wired):
    from qod_cli.main import app

    wired["admin_state"] = "unreachable"
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "QOD_ADMIN_PASSWORD" not in wired["env"]


def test_start_migrates_a_stored_password(runner, wired):
    from qod_cli.config import load_start_env, save_start_env
    from qod_cli.main import app

    save_start_env({"QOD_ADMIN_PASSWORD": "legacy"})
    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    assert "Your current admin password is: legacy" in result.output
    assert "QOD_ADMIN_PASSWORD" not in load_start_env()
    assert "QOD_ADMIN_PASSWORD" not in wired["env"]
