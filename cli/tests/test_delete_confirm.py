"""Typed-name confirmation on `qod tenant delete` and `qod database delete`: on a terminal the name
must be typed before anything is sent; without one nothing is asked (test_commands_table runs both
deletes that way). No flag or variable skips the question on a terminal."""

import httpx
import pytest
import respx
from typer.testing import CliRunner

from qod_cli.commands import _confirm
from qod_cli.main import app

BASE = "http://localhost:20900"

DELETES = [
    (["tenant", "delete", "acme"], "/api/tenant/delete", "acme"),
    (["database", "delete", "--tenant", "acme", "--name", "acme_tpch"], "/api/database/delete", "acme_tpch"),
]


def _invoke(monkeypatch, argv, answer):
    monkeypatch.setattr(_confirm, "_stdin_isatty", lambda: True)
    return CliRunner().invoke(app, argv, input=answer)


@pytest.mark.parametrize("argv,path,name", DELETES)
@pytest.mark.parametrize("answer", ["\n", "y\n", "yes\n", "ACME_TPCH\n", "acme_tpch2\n", "acm\n"])
def test_a_terminal_answer_other_than_the_name_sends_nothing(monkeypatch, argv, path, name, answer):
    if answer.strip() == name:
        pytest.skip("that answer is the name")
    with respx.mock(base_url=BASE, assert_all_called=False) as mock:
        route = mock.post(path).mock(return_value=httpx.Response(200, json={}))
        result = _invoke(monkeypatch, argv, answer)
    assert result.exit_code == 1, result.output
    assert "aborted; nothing was deleted." in result.output
    assert not route.called


@pytest.mark.parametrize("argv,path,name", DELETES)
def test_end_of_input_on_a_terminal_sends_nothing(monkeypatch, argv, path, name):
    with respx.mock(base_url=BASE, assert_all_called=False) as mock:
        route = mock.post(path).mock(return_value=httpx.Response(200, json={}))
        result = _invoke(monkeypatch, argv, "")
    assert result.exit_code == 1, result.output
    assert not route.called


@pytest.mark.parametrize("argv,path,name", DELETES)
@pytest.mark.parametrize("pad", ["", "  "])
def test_typing_the_name_on_a_terminal_deletes(monkeypatch, argv, path, name, pad):
    with respx.mock(base_url=BASE, assert_all_called=False) as mock:
        route = mock.post(path).mock(return_value=httpx.Response(200, json={}))
        result = _invoke(monkeypatch, argv, f"{pad}{name}{pad}\n")
    assert result.exit_code == 0, result.output
    assert route.called
    assert f"Type '{name}' to delete it" in result.output


def test_the_database_prompt_says_whether_managed_storage_is_purged(monkeypatch):
    argv = ["database", "delete", "--tenant", "acme", "--name", "acme_tpch"]
    with respx.mock(base_url=BASE, assert_all_called=False) as mock:
        mock.post("/api/database/delete").mock(return_value=httpx.Response(200, json={}))
        kept = _invoke(monkeypatch, argv, "no\n")
        purged = _invoke(monkeypatch, [*argv, "--purge-managed-data"], "no\n")
    assert "kept for the retention window" in kept.output
    assert "purged now" in purged.output


@pytest.mark.parametrize("argv,path,name", DELETES)
def test_without_a_terminal_nothing_is_asked(monkeypatch, argv, path, name):
    monkeypatch.setattr(_confirm, "_stdin_isatty", lambda: False)
    with respx.mock(base_url=BASE, assert_all_called=False) as mock:
        route = mock.post(path).mock(return_value=httpx.Response(200, json={}))
        result = CliRunner().invoke(app, argv)
    assert result.exit_code == 0, result.output
    assert route.called
    assert "Type '" not in result.output


def test_typed_name_matches_needs_the_exact_name():
    assert _confirm.typed_name_matches(" acme ", "acme")
    assert not _confirm.typed_name_matches("Acme", "acme")
    assert not _confirm.typed_name_matches("", "")
