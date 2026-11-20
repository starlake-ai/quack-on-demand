"""Typed-name confirmation for deletes that cannot be undone.

On a terminal the user types the name of what is deleted; anything else aborts with nothing sent.
Without a terminal (scripts, CI, an agent) nothing is asked: a script that wants no prompt on a
terminal redirects stdin. There is deliberately no flag or variable that skips the question on a
terminal, as with NUKE=1: a bypass becomes the incantation everyone copies.
"""

import sys

import typer


def _stdin_isatty() -> bool:
    # A function of its own, so tests can stand in a terminal (CliRunner swaps sys.stdin).
    return sys.stdin.isatty()


def typed_name_matches(answer: str, expected: str) -> bool:
    """The answer names `expected` exactly, surrounding spaces aside."""
    return bool(expected) and answer.strip() == expected


def confirm_typed_name(expected: str, consequences: str) -> None:
    """Ask, on a terminal, for `expected` to be typed before a delete; exit 1 on anything else."""
    if not _stdin_isatty():
        return
    typer.echo(consequences, err=True)
    answer = typer.prompt(
        f"Type '{expected}' to delete it (anything else aborts)", default="", show_default=False, err=True
    )
    if not typed_name_matches(answer, expected):
        typer.echo("aborted; nothing was deleted.", err=True)
        raise typer.Exit(1)
