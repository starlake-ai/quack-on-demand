import os
import shutil
import subprocess
import sys
from pathlib import Path

import typer

from .. import launcher
from ._launch import _exec, resolve_jar, resolve_java

# Tenant-db Postgres databases created by the bundled demo manifests; NUKE=1
# drops them alongside the control-plane DB, mirroring run-jar.sh.
_DEMO_DBS = ("acme_tpch", "globex_tpcds")

# (env var, loader script, tenant-db, schema) per run-jar.sh's LOAD block.
_LOAD_PLANS = (
    ("LOAD_TPCH", "load-tpch-dbgen.sh", "acme_tpch", "tpch1"),
    ("LOAD_TPCDS", "load-tpcds-dbgen.sh", "globex_tpcds", "tpcds1"),
    ("LOAD_SSB", "load-ssb-dbgen.sh", "acme_tpch", "ssb1"),
)


def _pg_coords(env: dict) -> dict:
    return {
        "host": env.get("QOD_PG_HOST", "localhost"),
        "port": env.get("QOD_PG_PORT", "5432"),
        "user": env.get("QOD_PG_USER", "postgres"),
        "password": env.get("QOD_PG_PASSWORD", "azizam"),
        "admin_db": env.get("QOD_PG_ADMIN_DB", "postgres"),
        "dbname": env.get("QOD_PG_DBNAME", "qod"),
    }


def _psql(pg: dict, sql: str):
    return subprocess.run(
        ["psql", "-h", pg["host"], "-p", pg["port"], "-U", pg["user"],
         "-d", pg["admin_db"], "-tAc", sql],
        env={**os.environ, "PGPASSWORD": pg["password"]},
        capture_output=True,
        text=True,
    )


def _nuke(state_dir: Path, pg: dict) -> None:
    typer.echo("NUKE=1: tearing down state...", err=True)
    if shutil.which("psql"):
        for db in (pg["dbname"], *_DEMO_DBS):
            typer.echo(f"  dropping Postgres database: {db}", err=True)
            _psql(pg, f'DROP DATABASE IF EXISTS "{db}" WITH (FORCE)')
    else:
        typer.echo(
            "  WARN: psql not found; skipping Postgres DB drops (local wipes still proceed)",
            err=True,
        )
    for d in ("ducklake", "state", "certs"):
        target = state_dir / d
        if target.is_dir():
            typer.echo(f"  wiping {target}", err=True)
            shutil.rmtree(target, ignore_errors=True)


def _ensure_catalog_db(pg: dict) -> None:
    """Idempotent CREATE DATABASE of the control-plane DB, so a brand-new
    install does not fail at the first Hikari connection. Skipped (like
    run-jar.sh) when psql is not installed."""
    if not shutil.which("psql"):
        return
    exists = _psql(pg, f"SELECT 1 FROM pg_database WHERE datname='{pg['dbname']}'")
    if "1" not in (exists.stdout or ""):
        typer.echo(f"catalog db: creating '{pg['dbname']}'...", err=True)
        _psql(pg, f'CREATE DATABASE "{pg["dbname"]}"')


def _spawn_loaders(env: dict, scripts: dict, state_dir: Path, pg: dict) -> None:
    """run-jar.sh's LOAD_* block: background the selected benchmark loaders
    before exec replaces this process (they write DuckLake directly through
    the provisioned duckdb CLI; the orphans survive the exec)."""
    load_tpc = env.get("LOAD_TPC", "")
    selected = [
        (env.get(var) or load_tpc, script, db, schema)
        for var, script, db, schema in _LOAD_PLANS
        if (env.get(var) or load_tpc)
    ]
    if not selected:
        return
    if sys.platform == "win32":
        typer.echo(
            "LOAD_* seeding is not yet supported on Windows through qod start "
            "(the bundled loaders are bash); use the repo's PowerShell loaders "
            "(scripts/load-*-dbgen.ps1) instead.",
            err=True,
        )
        return
    profile = env.get("DEMO", "full")
    manifest = (
        "classpath:bootstrap-demo-minimal.yaml"
        if profile == "minimal"
        else "classpath:bootstrap-demo.yaml"
    )
    env.setdefault("QOD_BOOTSTRAP_YAML", manifest)
    anchor = Path(env["QOD_DUCKLAKE_DATA_PATH"]).parent
    typer.echo(
        f"load-tpc: profile={profile}, spawning {len(selected)} loader(s) in background",
        err=True,
    )
    for sf, script, db, schema in selected:
        loader_env = {
            **env,
            "SF": sf,
            "PG_HOST": pg["host"],
            "PG_PORT": pg["port"],
            "PG_USER": pg["user"],
            "PG_PASS": pg["password"],
            "PG_ADMIN_DB": pg["admin_db"],
            "DB_NAME": db,
            "SCHEMA_NAME": schema,
            "DATA_PATH": str(anchor / db),
        }
        subprocess.Popen(["bash", str(scripts[script])], env=loader_env, cwd=state_dir)


def start(
    ctx: typer.Context,
    version: str = typer.Option(
        None,
        "--version",
        envvar="QOD_VERSION",
        help="Manager release to run (default: the latest release).",
    ),
    jar: Path = typer.Option(None, "--jar", help="Run this local jar instead of downloading."),
    yes: bool = typer.Option(
        False, "--yes", "-y", help="Do not prompt before downloading a Java runtime."
    ),
):
    """Run a quack-on-demand manager against your Postgres (scripts/run-jar.sh
    without the checkout). Postgres is assumed reachable (QOD_PG_* env vars);
    supports run-jar's LOAD_TPCH/LOAD_TPCDS/LOAD_SSB/LOAD_TPC, DEMO, NUKE,
    JAVA_OPTS, JAVA_BIN, JAR_CACHE_DIR, DUCKDB_VERSION, and DUCKDB_CACHE_DIR."""
    java = resolve_java(yes)
    # Absolute before the chdir below, or a relative --jar breaks at exec.
    jar_path = jar.resolve() if jar is not None else resolve_jar(version)

    app_home = launcher.default_cache_dir()
    try:
        duckdb_bin = launcher.ensure_duckdb_cli(app_home)
        libduckdb = launcher.ensure_libduckdb(app_home)
    except Exception as e:
        typer.echo(f"could not provision duckdb: {e}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")
    env = launcher.runtime_env(
        dict(os.environ), app_home, duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb
    )

    # Durable state anchor: certs/ and any relative paths land here, and the
    # DuckLake data path defaults under it (run-jar anchors these at the repo).
    state_dir = launcher.default_data_dir()
    state_dir.mkdir(parents=True, exist_ok=True)
    env.setdefault("QOD_DUCKLAKE_DATA_PATH", str(state_dir / "ducklake" / "data"))

    pg = _pg_coords(env)
    if env.get("NUKE") == "1":
        _nuke(state_dir, pg)
    _ensure_catalog_db(pg)
    loader_scripts = launcher.materialize_loader_scripts(app_home / "scripts")
    _spawn_loaders(env, loader_scripts, state_dir, pg)

    os.chdir(state_dir)
    _exec(
        launcher.build_jar_command(
            java, str(jar_path), list(ctx.args), java_opts=env.get("JAVA_OPTS")
        ),
        env,
    )
