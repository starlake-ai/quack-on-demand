import signal
import socket
import sys
from pathlib import Path

import typer

from .. import launcher
from ..agent import Agent, default_advertise_host


def agent(
    manager: str = typer.Option(..., "--manager", envvar="QOD_MANAGER_URL", help="Manager base URL, https://host:20900."),
    join_token: str = typer.Option(..., "--join-token", envvar="QOD_FLEET_JOIN_TOKEN"),
    name: str = typer.Option(socket.gethostname(), "--name", help="Server identity; must be unique in the fleet."),
    advertise_host: str = typer.Option(None, "--advertise-host", help="Address the manager dials; default: first non-loopback IPv4. Set it explicitly on multi-NIC hosts."),
    bind_host: str = typer.Option(None, "--bind-host", help="Interface the node listens on; default: the advertise host. 0.0.0.0 to listen everywhere."),
    node_port: int = typer.Option(21900, "--node-port"),
    duckdb_bin: Path = typer.Option(None, "--duckdb-bin", help="duckdb executable; default: provisioned into the qod cache."),
    state_dir: Path = typer.Option(None, "--state-dir", help="Where the node pidfile lives; default: the qod cache."),
    insecure: bool = typer.Option(False, "--insecure", help="Allow a plain http:// manager URL."),
):
    """Join this server to a quack-on-demand fleet and run the node the manager assigns (Linux, macOS)."""
    if sys.platform == "win32":
        typer.echo("qod agent is not available on Windows yet", err=True)
        raise typer.Exit(2)
    cache = launcher.default_cache_dir()
    spawn_sh, _ = launcher.materialize_spawn_scripts(cache / "scripts")
    exe = duckdb_bin or (launcher.ensure_duckdb_cli(cache) / "duckdb")
    # The version is only known for the binary qod provisioned; a caller-supplied one reports None.
    version = None if duckdb_bin else launcher.duckdb_version()
    adv = advertise_host or default_advertise_host()
    runner = Agent(manager, join_token, name=name, advertise_host=adv, bind_host=bind_host or adv,
                   node_port=node_port, spawn_script=spawn_sh, duckdb_bin=exe,
                   state_dir=state_dir or cache / "agent", insecure=insecure, duckdb_version=version)

    # SIGTERM (systemd stop, kill) must unwind through run_forever's finally so the node, which
    # lives in its own session, is stopped with the agent rather than left behind.
    def _on_term(signum, frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, _on_term)
    runner.run_forever()
