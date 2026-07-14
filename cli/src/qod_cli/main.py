import typer

from . import __version__

app = typer.Typer(no_args_is_help=True, add_completion=False, help="quack-on-demand CLI")


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(f"qod {__version__}")
        raise typer.Exit()


@app.callback()
def _root(
    version: bool = typer.Option(
        False, "--version", callback=_version_callback, is_eager=True, help="Print version and exit."
    ),
) -> None:
    pass


def main() -> None:
    app()
