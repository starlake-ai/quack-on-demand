import pytest
import typer

from qod_cli.commands.sql_cmd import run_file, split_statements


def _stmts(text):
    return [stmt for _, stmt in split_statements(text)]


def test_split_basic_and_lines():
    got = split_statements("SELECT 1;\nSELECT 2;\n")
    assert got == [(1, "SELECT 1"), (2, "SELECT 2")]


def test_split_semicolon_in_string_and_identifier():
    assert _stmts("SELECT ';' AS a; SELECT 1;") == ["SELECT ';' AS a", "SELECT 1"]
    assert _stmts("SELECT 'it''s;fine'; SELECT 2;") == ["SELECT 'it''s;fine'", "SELECT 2"]
    assert _stmts('SELECT ";" FROM "we;ird"; SELECT 3;') == [
        'SELECT ";" FROM "we;ird"',
        "SELECT 3",
    ]


def test_split_comments_hide_semicolons():
    assert _stmts("SELECT 1 -- trailing ; comment\n; SELECT 2;") == [
        "SELECT 1 -- trailing ; comment",
        "SELECT 2",
    ]
    assert _stmts("SELECT 1 /* ; */; SELECT 2;") == ["SELECT 1 /* ; */", "SELECT 2"]
    # DuckDB nests block comments.
    assert _stmts("SELECT 1 /* a /* ; */ b ; */; SELECT 2;") == [
        "SELECT 1 /* a /* ; */ b ; */",
        "SELECT 2",
    ]


def test_split_dollar_quoted():
    assert _stmts("SELECT $$a;b$$; SELECT 2;") == ["SELECT $$a;b$$", "SELECT 2"]
    assert _stmts("SELECT $t$x;$$;y$t$; SELECT 2;") == ["SELECT $t$x;$$;y$t$", "SELECT 2"]


def test_split_drops_empty_and_comment_only_chunks():
    assert _stmts(";;  ;\nSELECT 1; -- done\n") == ["SELECT 1"]
    # A leading comment stays attached to its statement (DuckDB parses it;
    # fidelity beats stripping), but a trailing comment-only chunk is dropped.
    assert _stmts("/* header */\nSELECT 1;\n/* footer */") == ["/* header */\nSELECT 1"]


def test_split_final_statement_without_semicolon():
    assert _stmts("SELECT 1;\nSELECT 2") == ["SELECT 1", "SELECT 2"]


class _FakeClient:
    def __init__(self, fail_on=None):
        self.executed = []
        self.fail_on = fail_on

    def query(self, stmt):
        self.executed.append(stmt)
        if self.fail_on and self.fail_on in stmt:
            raise RuntimeError("boom")
        return {"columns": [], "rows": []}


def test_run_file_executes_in_order(tmp_path, monkeypatch):
    from qod_cli.commands import sql_cmd

    monkeypatch.setattr(sql_cmd, "render_table", lambda result, mode: None)
    script = tmp_path / "s.sql"
    script.write_text("SELECT 1;\nSELECT 2;\n")
    client = _FakeClient()
    run_file(client, script, "table")
    assert client.executed == ["SELECT 1", "SELECT 2"]


def test_run_file_fails_fast_with_statement_and_line(tmp_path, monkeypatch, capsys):
    from qod_cli.commands import sql_cmd

    monkeypatch.setattr(sql_cmd, "render_table", lambda result, mode: None)
    script = tmp_path / "s.sql"
    script.write_text("SELECT 1;\n\nSELECT boom;\nSELECT 3;\n")
    client = _FakeClient(fail_on="boom")
    with pytest.raises(typer.Exit) as exc:
        run_file(client, script, "table")
    assert exc.value.exit_code == 1
    # fail fast: statement 3 never ran
    assert client.executed == ["SELECT 1", "SELECT boom"]
    err = capsys.readouterr().err
    assert "statement 2" in err
    assert "line 3" in err


def test_run_file_empty_script_errors(tmp_path):
    script = tmp_path / "empty.sql"
    script.write_text("-- nothing here\n")
    with pytest.raises(typer.Exit):
        run_file(_FakeClient(), script, "table")


def test_sql_file_and_statement_mutually_exclusive(runner, tmp_path):
    from qod_cli.main import app

    script = tmp_path / "s.sql"
    script.write_text("SELECT 1;")
    result = runner.invoke(app, ["sql", "SELECT 1", "--file", str(script)])
    assert result.exit_code != 0
    assert "not both" in result.output


def test_sql_file_missing_path_is_usage_error(runner):
    from qod_cli.main import app

    result = runner.invoke(app, ["sql", "--file", "/nonexistent/x.sql"])
    assert result.exit_code != 0
    assert "no such file" in result.output
