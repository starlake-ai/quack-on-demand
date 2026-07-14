from __future__ import annotations

from typing import Any

import typer

from ..output import render
from ..rest import ApiError, RestClient


def call(
    ctx: typer.Context,
    method: str,
    path: str,
    params: dict | None = None,
    body: Any | None = None,
    text: bool = False,
) -> Any:
    try:
        data = RestClient(ctx.obj.settings).request(method, path, params=params, body=body, text=text)
    except ApiError as exc:
        typer.echo(f"error: {exc}", err=True)
        raise typer.Exit(1)
    render(data, ctx.obj.json_output)
    return data


def kv_pairs(values: list[str] | None) -> dict:
    out: dict = {}
    for item in values or []:
        if "=" not in item:
            raise typer.BadParameter(f"expected KEY=VALUE, got {item!r}")
        key, _, value = item.partition("=")
        out[key] = value
    return out
