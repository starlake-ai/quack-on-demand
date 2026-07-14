import click
import typer

from ..sql import SqlClient, render_table


def repl(client, mode: str, input_fn=input) -> None:
    try:
        import readline  # noqa: F401  (line editing + history; pyreadline3 provides it on Windows)
    except ImportError:
        pass
    import sys

    buffer: list[str] = []
    while True:
        prompt = "qod> " if not buffer else "...> "
        try:
            line = input_fn(prompt)
        except EOFError:
            break
        except KeyboardInterrupt:
            buffer.clear()
            print(file=sys.stderr)
            continue
        if not buffer and line.strip() == "\\q":
            break
        buffer.append(line)
        if not line.rstrip().endswith(";"):
            continue
        statement = "\n".join(buffer)
        buffer.clear()
        try:
            render_table(client.query(statement), mode)
        except Exception as exc:
            print(f"error: {exc}", file=sys.stderr)


def _mode(ctx: typer.Context, csv_flag: bool) -> str:
    if csv_flag and ctx.obj.json_output:
        raise typer.BadParameter("--csv and --json are mutually exclusive")
    return "csv" if csv_flag else "json" if ctx.obj.json_output else "table"


def _connect(ctx: typer.Context) -> SqlClient:
    settings = ctx.obj.settings
    if not settings.sql_user:
        raise typer.BadParameter("no SQL user configured; run qod login or set QOD_USER")
    if not settings.sql_password:
        settings.sql_password = typer.prompt(f"Password for {settings.sql_user}", hide_input=True)
    return SqlClient.connect(settings)


def sql(
    ctx: typer.Context,
    statement: str = typer.Argument(None, help="SQL to run; omit for the interactive REPL."),
    csv_flag: bool = typer.Option(False, "--csv", help="CSV to stdout."),
    tenant: str = typer.Option(None, "--tenant", help="Override the profile tenant."),
    pool: str = typer.Option(None, "--pool", help="Override the profile pool."),
    superuser: bool = typer.Option(None, "--superuser/--no-superuser"),
):
    """Run SQL against the FlightSQL edge."""
    settings = ctx.obj.settings
    if tenant is not None:
        settings.tenant = tenant
    if pool is not None:
        settings.pool = pool
    if superuser is not None:
        settings.superuser = superuser
    mode = _mode(ctx, csv_flag)
    if statement is None:
        try:
            with _connect(ctx) as client:
                repl(client, mode)
        except (click.ClickException, typer.Exit):
            raise
        except Exception as exc:
            typer.echo(f"error: {exc}", err=True)
            raise typer.Exit(1)
        return
    try:
        with _connect(ctx) as client:
            render_table(client.query(statement), mode)
    except (click.ClickException, typer.Exit):
        raise
    except Exception as exc:  # ADBC raises driver-specific exception types
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
