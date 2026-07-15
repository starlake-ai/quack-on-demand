import pytest

from qod_cli.commands.sql_cmd import repl


class FakeClient:
    def __init__(self):
        self.executed = []

    def query(self, sql):
        import pyarrow as pa

        self.executed.append(sql)
        if "boom" in sql:
            raise RuntimeError("syntax error near boom")
        return pa.table({"n": [1]})


def scripted(lines):
    it = iter(lines)

    def fake_input(prompt=""):
        try:
            return next(it)
        except StopIteration:
            raise EOFError

    return fake_input


def test_multiline_until_semicolon(capsys):
    client = FakeClient()
    repl(client, "csv", input_fn=scripted(["SELECT", "1 AS n;", "\\q"]))
    assert client.executed == ["SELECT\n1 AS n;"]


def test_error_does_not_exit_loop(capsys):
    client = FakeClient()
    repl(client, "csv", input_fn=scripted(["SELECT boom;", "SELECT 1 AS n;"]))
    assert len(client.executed) == 2
    assert "syntax error" in capsys.readouterr().err


def test_eof_exits():
    client = FakeClient()
    repl(client, "csv", input_fn=scripted([]))
    assert client.executed == []
