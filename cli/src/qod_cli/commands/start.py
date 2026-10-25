import os
import shutil
import subprocess
import sys
from pathlib import Path

import typer

from .. import admin_password, launcher, starflow
from ..config import config_path, load_start_env
from . import _starflow
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


def _confirm_nuke(pg: dict) -> None:
    """NUKE is irreversible (drops the control plane and demo tenant-dbs,
    wipes the per-user state dirs). On a terminal, require typing the
    control-plane db name so a pasted NUKE=1 cannot destroy data silently.
    Non-tty runs skip the prompt (scripted use unchanged; a script that
    wants no prompt redirects stdin). Deliberately NOT a password check:
    NUKE runs with the invoker's OS privileges and must keep working when
    the stack is too broken to verify credentials against."""
    if not sys.stdin.isatty():
        return
    expected = pg["dbname"]
    typer.echo(
        f"NUKE=1 will drop the '{expected}' control plane, the demo "
        "tenant-dbs, and wipe the local ducklake/state/certs dirs.",
        err=True,
    )
    answer = typer.prompt(f"Type '{expected}' to proceed (anything else aborts)")
    if answer != expected:
        typer.echo("aborted; nothing was touched.", err=True)
        raise typer.Exit(1)


def _nuke(state_dir: Path, pg: dict) -> None:
    _confirm_nuke(pg)
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
    before the manager boots (they write DuckLake directly through the
    provisioned duckdb CLI and run independently of the supervised JVM)."""
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


def _manager_running(manager_url: str) -> bool:
    """Seam over serve's GET /ready probe (tests pin it; never a real :20900)."""
    from .serve import _manager_running as probe

    return probe(manager_url)


def start(
    ctx: typer.Context,
    version: str = typer.Option(
        None,
        "--version",
        envvar="QOD_VERSION",
        help="Manager release to run (default: the latest release).",
    ),
    jar: Path = typer.Option(None, "--jar", help="Run this local jar instead of downloading."),
    demo: bool = typer.Option(
        False,
        "--demo",
        help="Run the self-contained demo instead: embedded ephemeral Postgres, seeded "
        "TPC-H, RLS/CLS showcase. Needs no external Postgres; all state is deleted on exit. "
        "(deprecated alias: use qod serve --demo)",
    ),
    with_starflow: bool = _starflow.WITH_STARFLOW,
    starflow_home: str = _starflow.STARFLOW_HOME,
    starflow_version: str = _starflow.STARFLOW_VERSION,
    starflow_port: int = _starflow.STARFLOW_PORT,
    starflow_url: str = _starflow.STARFLOW_URL,
    starflow_env_file: Path = _starflow.STARFLOW_ENV_FILE,
):
    """Run a quack-on-demand manager against your Postgres (scripts/run-jar.sh
    without the checkout). Postgres is assumed reachable (QOD_PG_* env vars);
    supports run-jar's LOAD_TPCH/LOAD_TPCDS/LOAD_SSB/LOAD_TPC, DEMO, NUKE,
    JAVA_OPTS, JAVA_BIN, JAR_CACHE_DIR, DUCKDB_VERSION, and DUCKDB_CACHE_DIR.
    Run `qod setup` once to persist QOD_PG_*/admin/API-key/TLS settings so you
    don't have to export them every time - a real env var still overrides it.
    With --demo, runs the self-contained demo instead (no Postgres needed,
    and qod setup's stored config is not applied - see qod setup --help).
    Ctrl-C tears the manager and its nodes down gracefully (same as qod stop).

    With --with-starflow, Starflow (the Starlake API + UI) runs next to the
    manager in a `starlake` database on the same Postgres server, paired for
    SSO, REST and FlightSQL; it is installed on first use and stopped by
    Ctrl-C and qod stop.

    No Postgres and just want to serve local data? Use qod serve."""
    try:
        from .. import __version__
        from ..launcher import newer_release_hint

        hint = newer_release_hint(__version__)
        if hint:
            typer.echo(hint, err=True)
    except Exception:
        pass  # purely decorative; must never block a start

    sf_request = _starflow.request(
        with_starflow, starflow_home, starflow_version, starflow_port, starflow_url,
        starflow_env_file,
    )
    if demo and sf_request is not None:
        typer.echo(
            "error: --with-starflow cannot run with the demo (ephemeral, insecure by design); "
            "use qod serve or qod start without --demo",
            err=True,
        )
        raise typer.Exit(1)
    if sf_request is not None:
        # qod cannot know, or inject, the running manager's session secret, so
        # the pair could never authenticate (same refusal as serve).
        port = {**load_start_env(), **os.environ}.get("QOD_ON_DEMAND_PORT") or "20900"
        manager_url = f"http://localhost:{port}"
        if _manager_running(manager_url):
            typer.echo(
                f"error: a manager is already running at {manager_url}; --with-starflow "
                "needs to launch it itself: stop it first (qod stop)",
                err=True,
            )
            raise typer.Exit(1)

    if demo:
        from .demo import run_demo

        typer.echo(
            "note: the demo moved to qod serve --demo (this alias will be removed in a "
            "future release)",
            err=True,
        )
        run_demo(ctx, version, jar)
        return
    java = resolve_java()
    # Absolute before the chdir below, or a relative --jar breaks at exec.
    jar_path = jar.resolve() if jar is not None else resolve_jar(version)

    app_home = launcher.default_cache_dir()
    try:
        duckdb_bin = launcher.ensure_duckdb_cli(app_home)
        libduckdb = launcher.ensure_libduckdb(app_home)
        node_bin = launcher.node_binary(jar_path, app_home)
    except Exception as e:
        typer.echo(f"could not provision the node runtime: {e}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")
    # `qod setup` persists QOD_*/PROXY_* vars to the CLI config file; a real
    # process env var still wins (same precedence as everywhere else in the
    # CLI: explicit > env var > file > built-in default).
    # The admin password is no longer kept in the [start] table (see admin_password).
    admin_password.migrate_stored_password(lambda line: typer.echo(line, err=True))
    base_env = {**load_start_env(), **os.environ}
    sf_plan = _starflow.prepare(sf_request, base_env, java) if sf_request else None
    env = launcher.runtime_env(
        base_env, duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb, qod_node_bin=node_bin
    )
    if sf_plan is not None:
        env.update(starflow.manager_env(sf_plan.url, sf_plan.secret))
    # The manager's startup banner names the CLI config file it was launched with.
    env["QOD_CONFIG_FILE"] = str(config_path())

    # Durable state anchor: certs/ and any relative paths land here, and the
    # DuckLake data path defaults under it (run-jar anchors these at the repo).
    state_dir = launcher.default_data_dir()
    state_dir.mkdir(parents=True, exist_ok=True)
    env.setdefault("QOD_DUCKLAKE_DATA_PATH", str(state_dir / "ducklake" / "data"))

    pg = _pg_coords(env)
    if env.get("NUKE") == "1":
        _nuke(state_dir, pg)
    _ensure_catalog_db(pg)
    # First boot of this control plane: the one time the admin password is
    # needed. Later boots pass none (the manager seeds insert-only).
    state = admin_password.control_plane_admin_state(pg, admin_password.admin_usernames(env))
    if state == admin_password.ABSENT:
        chosen = admin_password.first_boot_password(
            os.environ.get(admin_password.KEY), admin_password.is_interactive()
        )
        if chosen is None:
            typer.echo(admin_password.refusal("qod start"), err=True)
            raise typer.Exit(1)
        env[admin_password.KEY] = chosen
    loader_scripts = launcher.materialize_loader_scripts(app_home / "scripts")
    _spawn_loaders(env, loader_scripts, state_dir, pg)

    if sf_plan is not None:
        _starflow.start_after_ready(
            sf_plan,
            mgr_env=env,
            pg=starflow.PgCoords(
                host=pg["host"],
                port=int(pg["port"]),
                user=pg["user"],
                password=pg["password"],
                admin_db=pg["admin_db"],
            ),
        )

    os.chdir(state_dir)
    _exec(
        launcher.build_jar_command(
            java, str(jar_path), list(ctx.args), java_opts=env.get("JAVA_OPTS")
        ),
        env,
    )
