import json

import pytest

# pyarrow ships no wheel for Windows on ARM64, where pyproject markers skip it;
# render_table's output shape is platform-independent, so skip there.
pa = pytest.importorskip("pyarrow")

from qod_cli.sql import render_table


def fixture_table() -> pa.Table:
    return pa.table({"id": [1, 2], "name": ["a", "b"], "price": [1.5, None]})


def test_render_json(capsys):
    render_table(fixture_table(), "json")
    rows = json.loads(capsys.readouterr().out)
    assert rows == [
        {"id": 1, "name": "a", "price": 1.5},
        {"id": 2, "name": "b", "price": None},
    ]


def test_render_csv(capsys):
    render_table(fixture_table(), "csv")
    lines = capsys.readouterr().out.strip().splitlines()
    assert lines[0] == "id,name,price"
    assert lines[1] == "1,a,1.5"


def test_render_table_mode(capsys):
    render_table(fixture_table(), "table")
    captured = capsys.readouterr()
    assert "name" in captured.out and "a" in captured.out
    assert "2 rows" in captured.err
