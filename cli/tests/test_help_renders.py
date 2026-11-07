"""Every command's --help renders.

Typer prints help through Rich, which reads `[...]` in a help text as markup: `s3://bucket[/prefix]`
in `qod serve`'s help was a closing tag with no opening one, and `qod serve --help` crashed with a
MarkupError in 0.12.0. Walking the whole command tree catches any help text like it.
"""

import typer.main
from typer.testing import CliRunner

from qod_cli.main import app

runner = CliRunner()


def _command_paths(command, path=()):
    yield list(path)
    for name, sub in sorted(getattr(command, "commands", {}).items()):
        yield from _command_paths(sub, (*path, name))


def test_every_command_help_renders(isolated_env):
    paths = list(_command_paths(typer.main.get_command(app)))
    assert len(paths) > 100, "the walk should reach every subcommand"
    failures = []
    for path in paths:
        result = runner.invoke(app, [*path, "--help"])
        if result.exit_code != 0:
            failures.append(f"qod {' '.join(path)}: {type(result.exception).__name__}: {result.exception}")
    assert failures == []


def test_serve_help_names_the_bucket_forms(isolated_env):
    result = runner.invoke(app, ["serve", "--help"])
    assert result.exit_code == 0, result.exception
    assert "s3://bucket/prefix" in result.output
