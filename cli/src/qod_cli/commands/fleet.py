import signal
import socket
import sys
from pathlib import Path

import typer

from .. import launcher, qod_node
from ..fleet_join import FleetMember, default_advertise_host
from ..registry import covers
from ._run import call

app = typer.Typer(help="Fleet servers (runtimeType=fleet): join, list, approve, drain, undrain, remove.")


# Not a REST call (it drives the machine-to-machine /api/fleet/heartbeat), so no @covers.
@app.command()
def join(
    manager: str = typer.Option(..., "--manager", envvar="QOD_MANAGER_URL", help="Manager REST base URL: https:// through a TLS proxy, or http://host:20900 with --insecure (the REST port itself has no TLS)."),
    join_token: str = typer.Option(..., "--join-token", envvar="QOD_FLEET_JOIN_TOKEN", help="Fleet join token; prefer QOD_FLEET_JOIN_TOKEN, a flag value is visible in ps."),
    name: str = typer.Option(socket.gethostname(), "--name", envvar="QOD_FLEET_NAME", help="Server identity; must be unique in the fleet. In a container set it explicitly: the hostname is the container id."),
    advertise_host: str = typer.Option(None, "--advertise-host", envvar="QOD_FLEET_ADVERTISE_HOST", help="Address the manager dials; default: first non-loopback IPv4. Set it explicitly on multi-NIC hosts and in containers."),
    bind_host: str = typer.Option(None, "--bind-host", envvar="QOD_FLEET_BIND_HOST", help="Interface the node listens on; default: the advertise host. 0.0.0.0 to listen everywhere."),
    node_port: int = typer.Option(21900, "--node-port", envvar="QOD_FLEET_NODE_PORT"),
    qod_node_bin: Path = typer.Option(None, "--qod-node-bin", help="qod-node executable the node runs; default: the one qod bundles, installed in the qod cache beside the pinned libduckdb."),
    state_dir: Path = typer.Option(None, "--state-dir", help="Where the node pidfile lives; default: the qod cache."),
    insecure: bool = typer.Option(False, "--insecure", help="Allow a plain http:// manager URL (it does not relax TLS checks on https://)."),
):
    """Join this machine to a quack-on-demand fleet and keep running (Linux, macOS).

    Long-running: heartbeats the manager and runs the node it assigns until stopped
    (run it under systemd or launchd)."""
    if sys.platform == "win32":
        typer.echo("qod fleet join is not available on Windows yet", err=True)
        raise typer.Exit(2)
    cache = launcher.default_cache_dir()
    spawn_sh, _ = launcher.materialize_spawn_scripts(cache / "scripts")
    exe = qod_node_bin or qod_node.install(cache, launcher.ensure_libduckdb(cache))
    # Report what the binary that runs the node says, bundled or caller-supplied, not the pin: the
    # fleet listing is how an operator spots a stale engine. None when it cannot start here.
    version = qod_node.probe_version(exe)
    adv = advertise_host or default_advertise_host()
    # The default state dir keeps its pre-rename name ("agent") so an upgraded server still
    # finds, and reaps, a node orphaned by the previous version's pidfile.
    runner = FleetMember(manager, join_token, name=name, advertise_host=adv, bind_host=bind_host or adv,
                         node_port=node_port, spawn_script=spawn_sh, node_bin=exe,
                         state_dir=state_dir or cache / "agent", insecure=insecure, duckdb_version=version)

    # SIGTERM (systemd stop, kill) must unwind through run_forever's finally so the node, which
    # lives in its own session, is stopped with this process rather than left behind.
    def _on_term(signum, frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, _on_term)
    runner.run_forever()


@app.command()
@covers("GET", "/api/fleet/servers")
def servers(ctx: typer.Context):
    """List joined servers with liveness, capacity and assignment."""
    call(ctx, "GET", "/api/fleet/servers")


@app.command()
@covers("POST", "/api/fleet/server/drain", {"name": "NAME"})
def drain(ctx: typer.Context, name: str = typer.Argument(..., help="Server name.")):
    """Release the server's node and stop scheduling onto it."""
    call(ctx, "POST", "/api/fleet/server/drain", body={"name": name})


@app.command()
@covers("POST", "/api/fleet/server/undrain", {"name": "NAME"})
def undrain(ctx: typer.Context, name: str = typer.Argument(...)):
    """Make a drained server schedulable again."""
    call(ctx, "POST", "/api/fleet/server/undrain", body={"name": name})


@app.command()
@covers("POST", "/api/fleet/server/remove", {"name": "NAME"})
def remove(ctx: typer.Context, name: str = typer.Argument(...)):
    """Forget a server: a pending one at any time, an approved one once drained or unreachable (stop its `qod fleet join`, or it re-joins)."""
    call(ctx, "POST", "/api/fleet/server/remove", body={"name": name})


@app.command()
@covers("POST", "/api/fleet/server/approve", {"name": "NAME"})
def approve(ctx: typer.Context, name: str = typer.Argument(..., help="Server name.")):
    """Let a server that joined from outside QOD_FLEET_AUTO_APPROVE take nodes."""
    call(ctx, "POST", "/api/fleet/server/approve", body={"name": name})
