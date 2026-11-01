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


def duplicate_names_table() -> pa.Table:
    # SELECT 17 AS id, 23 AS id: a valid result whose column names repeat.
    return pa.table([pa.array([17]), pa.array([23])], names=["id", "id"])


def test_render_json_keeps_every_duplicate_column(capsys):
    render_table(duplicate_names_table(), "json")
    rows = json.loads(capsys.readouterr().out)
    assert rows == [{"id": 17, "id_1": 23}]


def test_render_json_skips_a_suffix_another_column_already_has(capsys):
    table = pa.table([pa.array([1]), pa.array([2]), pa.array([3])], names=["id", "id_1", "id"])
    render_table(table, "json")
    rows = json.loads(capsys.readouterr().out)
    assert rows == [{"id": 1, "id_1": 2, "id_2": 3}]


def test_render_csv_keeps_the_result_header(capsys):
    render_table(duplicate_names_table(), "csv")
    lines = capsys.readouterr().out.strip().splitlines()
    assert lines == ["id,id", "17,23"]


def test_render_table_mode_with_duplicate_columns(capsys):
    render_table(duplicate_names_table(), "table")
    captured = capsys.readouterr()
    assert "17" in captured.out and "23" in captured.out
    assert "1 rows" in captured.err
