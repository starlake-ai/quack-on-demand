import os
from pathlib import Path

import typer

from .. import demo_s3, launcher, starflow
from ..config import load_start_env
from . import _starflow
from ._launch import _exec, resolve_jar, resolve_java

# The jar deletes its run folder in the user's bucket as it stops, and Ctrl-C's stop sweep
# SIGKILLs after FORCE_AFTER seconds (10 by default): give it room, unless the user chose a value.
BUCKET_FORCE_AFTER = "90"


def run_demo(
    ctx: typer.Context,
    version: str | None,
    jar: Path | None,
    s3=None,
    starflow_request=None,
    ready_timeout: float = 180.0,
) -> None:
    """The `qod serve --demo` path (deprecated alias: `qod start --demo`): the
    self-contained quack-on-demand demo (embedded ephemeral Postgres, seeded
    data). Kept in its own module so the demo provisioning stays independent
    of the real-manager start path.

    `s3` is what `demo_s3.request` settled: None for the demo's temp folder, a
    `LocalRequest` to run a throwaway store for as long as the jar runs, or a
    `BucketRequest` for the user's own bucket. `starflow_request` (from
    `--with-starflow`) also runs Starflow, its state in a folder deleted on exit."""
    java = resolve_java()
    jar_path = jar if jar is not None else resolve_jar(version)

    # The jar spawns nodes through the spawn script and the `duckdb` CLI, and
    # its native client links libduckdb; all three only exist in a repo
    # checkout. Provision them next to the jar cache so the demo runs from any
    # working directory.
    app_home = launcher.default_cache_dir()
    try:
        duckdb_bin = launcher.ensure_duckdb_cli(app_home)
        libduckdb = launcher.ensure_libduckdb(app_home)
        node_bin = launcher.node_binary(jar_path, app_home)
    except Exception as e:
        typer.echo(f"could not provision the node runtime: {e}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")
    env = launcher.runtime_env(
        dict(os.environ), duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb,
        qod_node_bin=node_bin,
    )
    cmd = launcher.build_jar_command(java, str(jar_path), ["demo", *ctx.args])

    # Before the store starts: a failed Starflow install then leaves nothing running.
    sf_plan = None
    if starflow_request is not None:
        sf_plan = _starflow.prepare_demo(
            starflow_request, {**load_start_env(), **os.environ}, java
        )

    store = None
    try:
        if isinstance(s3, demo_s3.LocalRequest):
            try:
                demo_s3.sweep_orphan()
                store = demo_s3.LocalStore.start(launcher.ensure_weed(app_home))
            except Exception as e:
                typer.echo(f"could not start the local S3 store: {e}", err=True)
                raise typer.Exit(1)
            env = demo_s3.local_env(env, store)
        elif isinstance(s3, demo_s3.BucketRequest):
            env = demo_s3.bucket_env(env, s3)
            if not os.environ.get("FORCE_AFTER") and not load_start_env().get("FORCE_AFTER"):
                os.environ["FORCE_AFTER"] = BUCKET_FORCE_AFTER
        else:
            env = demo_s3.plain_env(env)

        if sf_plan is not None:
            env.update(starflow.manager_env(sf_plan.url, sf_plan.secret))
            _starflow.start_demo_after_ready(
                sf_plan,
                mgr_env=env,
                loopback_only=isinstance(s3, demo_s3.BucketRequest),
                ready_timeout=ready_timeout,
            )

        _exec(cmd, env)
    finally:
        # Starflow first, the store last, on every path out of the jar: the jar may still use the
        # store as it shuts down. Nested, so that a second interrupt during Starflow's stop still
        # stops the store.
        try:
            if sf_plan is not None:
                _starflow.finish_demo(sf_plan)
        finally:
            if store is not None:
                store.stop()
