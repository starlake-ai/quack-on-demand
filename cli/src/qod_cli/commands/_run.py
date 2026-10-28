from __future__ import annotations

from typing import Any

import typer

from ..fix_actions import action_lines
from ..output import render
from ..rest import ApiError, RestClient


def call(
    ctx: typer.Context,
    method: str,
    path: str,
    params: dict | None = None,
    body: Any | None = None,
    text: bool = False,
    quiet: bool = False,
) -> Any:
    try:
        data = RestClient(ctx.obj.settings).request(method, path, params=params, body=body, text=text)
    except ApiError as exc:
        typer.echo(f"error: {exc}", err=True)
        for line in action_lines(exc.actions, (exc.details or {}).get("server")):
            typer.echo(line, err=True)
        raise typer.Exit(1)
    if not quiet:
        render(data, ctx.obj.json_output)
    return data


def warn_init_sql(data: Any) -> None:
    """Says on stderr that the manager could not check a tenant admin's initSql (no local qod-node):
    the nodes still run only its allowlisted statements. A refused one is the call's own error."""
    if isinstance(data, dict):
        warning = data.get("initSqlWarning") or (data.get("db") or {}).get("initSqlWarning")
        if warning:
            typer.echo(f"warning: {warning}", err=True)


def kv_pairs(values: list[str] | None) -> dict:
    out: dict = {}
    for item in values or []:
        if "=" not in item:
            raise typer.BadParameter(f"expected KEY=VALUE, got {item!r}")
        key, _, value = item.partition("=")
        out[key] = value
    return out
