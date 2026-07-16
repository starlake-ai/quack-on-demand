from dataclasses import dataclass

import typer

from . import __version__
from .config import Settings, load_settings

app = typer.Typer(no_args_is_help=True, add_completion=False, help="quack-on-demand CLI")


@dataclass
class AppCtx:
    settings: Settings
    json_output: bool
    profile: str


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(f"qod {__version__}")
        raise typer.Exit()


@app.callback()
def _root(
    ctx: typer.Context,
    profile: str = typer.Option("default", "--profile", envvar="QOD_PROFILE", help="Named profile."),
    json_output: bool = typer.Option(False, "--json", help="Print raw JSON responses."),
    version: bool = typer.Option(
        False, "--version", callback=_version_callback, is_eager=True, help="Print version and exit."
    ),
) -> None:
    ctx.obj = AppCtx(settings=load_settings(profile), json_output=json_output, profile=profile)


@app.command()
def health(ctx: typer.Context):
    """Liveness plus pool/node counts (open endpoint)."""
    from .commands._run import call

    call(ctx, "GET", "/health")


from .commands import config_cmd  # noqa: E402

app.add_typer(config_cmd.app, name="config")

from .commands import auth  # noqa: E402

app.command()(auth.login)
app.command()(auth.logout)
app.command()(auth.whoami)
app.add_typer(auth.app, name="auth")

from .commands import database, tenant  # noqa: E402

app.add_typer(tenant.app, name="tenant")
app.add_typer(database.app, name="database")

from .commands import node, pool  # noqa: E402

app.add_typer(pool.app, name="pool")
app.add_typer(node.app, name="node")
app.add_typer(node.statement_app, name="statement")

from .commands import group, membership, role, user  # noqa: E402

app.add_typer(user.app, name="user")
app.add_typer(role.app, name="role")
app.add_typer(group.app, name="group")
app.add_typer(membership.app, name="membership")

from .commands import catalog, tag  # noqa: E402

app.add_typer(catalog.app, name="catalog")
app.add_typer(tag.app, name="tag")

from .commands import federation, maintenance, manifest  # noqa: E402

app.add_typer(maintenance.app, name="maintenance")
app.add_typer(manifest.app, name="manifest")
app.add_typer(federation.app, name="federation")

from .commands import telemetry  # noqa: E402

app.add_typer(telemetry.audit_app, name="audit")
app.add_typer(telemetry.history_app, name="history")
app.command()(telemetry.usage)

from .commands import sql_cmd  # noqa: E402

app.command("sql")(sql_cmd.sql)

from .commands import demo, start, stop  # noqa: E402

app.command(
    "demo", context_settings={"allow_extra_args": True, "ignore_unknown_options": True}
)(demo.demo)
app.command(
    "start", context_settings={"allow_extra_args": True, "ignore_unknown_options": True}
)(start.start)
app.command("stop")(stop.stop)


def main() -> None:
    app()
