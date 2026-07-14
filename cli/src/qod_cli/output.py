"""Human-readable Rich rendering, or raw JSON with --json (the stable
scripting interface)."""

from __future__ import annotations

import json
from typing import Any

from rich.console import Console
from rich.table import Table

_console = Console(soft_wrap=True)


def _cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        return json.dumps(value, default=str)
    return str(value)


def _render_rows(rows: list[dict]) -> None:
    columns: list[str] = []
    for row in rows:
        for key in row:
            if key not in columns:
                columns.append(key)
    table = Table(show_header=True, header_style="bold")
    for col in columns:
        table.add_column(col)
    for row in rows:
        table.add_row(*(_cell(row.get(col)) for col in columns))
    _console.print(table)


def render(data: Any, as_json: bool) -> None:
    if as_json:
        print(json.dumps(data, indent=2, default=str))
        return
    if data is None:
        print("ok")
        return
    if isinstance(data, str):
        print(data)
        return
    if isinstance(data, dict) and len(data) == 1:
        (only_value,) = data.values()
        if isinstance(only_value, list):
            data = only_value
    if isinstance(data, list):
        if data and all(isinstance(item, dict) for item in data):
            _render_rows(data)
        else:
            for item in data:
                print(_cell(item))
        return
    if isinstance(data, dict):
        table = Table(show_header=False)
        table.add_column("key", style="bold")
        table.add_column("value")
        for key, value in data.items():
            table.add_row(key, _cell(value))
        _console.print(table)
        return
    print(_cell(data))
