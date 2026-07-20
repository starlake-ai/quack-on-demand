import importlib.util

import pytest

from qod_cli.commands.sql_cmd import repl

# pyarrow ships no wheel for Windows on ARM64, where pyproject markers skip it;
# tests whose FakeClient materializes an Arrow table can't run there.
requires_pyarrow = pytest.mark.skipif(
    importlib.util.find_spec("pyarrow") is None, reason="pyarrow unavailable on this platform"
)


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


@requires_pyarrow
def test_multiline_until_semicolon(capsys):
    client = FakeClient()
    repl(client, "csv", input_fn=scripted(["SELECT", "1 AS n;", "\\q"]))
    assert client.executed == ["SELECT\n1 AS n;"]


@requires_pyarrow
def test_error_does_not_exit_loop(capsys):
    client = FakeClient()
    repl(client, "csv", input_fn=scripted(["SELECT boom;", "SELECT 1 AS n;"]))
    assert len(client.executed) == 2
    assert "syntax error" in capsys.readouterr().err


def test_eof_exits():
    client = FakeClient()
    repl(client, "csv", input_fn=scripted([]))
    assert client.executed == []
