import pathlib

import pytest

from qod_cli import launcher


@pytest.fixture
def wired(monkeypatch, tmp_path):
    """Stub every provisioning step and capture the exec; returns the capture dict."""
    from qod_cli.commands import start as start_cmd

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    captured = {}
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(
        launcher, "ensure_duckdb_cli", lambda cache_dir=None, **kw: tmp_path / "duckdb" / "bin"
    )
    monkeypatch.setattr(
        launcher, "ensure_libduckdb", lambda cache_dir=None, **kw: tmp_path / "duckdb" / "lib"
    )
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


def test_start_wires_native_client_library_path(runner, wired):
    from qod_cli.main import app

    result = runner.invoke(app, ["start", "--jar", str(wired["jar"])])
    assert result.exit_code == 0, result.output
    libvar = wired["env"].get("DYLD_LIBRARY_PATH") or wired["env"].get("LD_LIBRARY_PATH")
    assert libvar and str(pathlib.Path("duckdb") / "lib") in libvar


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
