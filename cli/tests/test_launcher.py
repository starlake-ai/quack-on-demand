import sys

import pytest
import httpx

from qod_cli.launcher import cached_release_hint, newer_release_hint, upgrade_command

PYPI_URL = "https://pypi.org/pypi/qod/json"


def _pypi_response(version: str) -> httpx.Response:
    return httpx.Response(200, json={"info": {"version": version}})


def test_newer_release_hint_names_both_versions_when_pypi_is_ahead(respx_mock):
    respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("0.9.5"))
    hint = newer_release_hint("0.9.3")
    assert hint is not None
    assert "0.9.5" in hint
    assert "0.9.3" in hint
    assert f"upgrade with: {upgrade_command()}" in hint


def test_newer_release_hint_is_none_when_already_current(respx_mock):
    respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("0.9.3"))
    assert newer_release_hint("0.9.3") is None


def test_newer_release_hint_is_none_on_pypi_server_error(respx_mock):
    respx_mock.get(PYPI_URL).mock(return_value=httpx.Response(500))
    assert newer_release_hint("0.9.3") is None


def test_newer_release_hint_is_none_on_timeout(respx_mock):
    respx_mock.get(PYPI_URL).mock(side_effect=httpx.TimeoutException("timed out"))
    assert newer_release_hint("0.9.3") is None


def test_newer_release_hint_is_none_on_connect_error(respx_mock):
    respx_mock.get(PYPI_URL).mock(side_effect=httpx.ConnectError("no network"))
    assert newer_release_hint("0.9.3") is None


def test_newer_release_hint_is_none_for_a_dev_build_and_skips_the_http_call(respx_mock):
    route = respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("99.0.0"))
    assert newer_release_hint("0.9.3.dev0") is None
    assert not route.called


def test_newer_release_hint_honours_the_opt_out_env(respx_mock, monkeypatch):
    route = respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("99.0.0"))
    monkeypatch.setenv("QOD_NO_UPDATE_CHECK", "1")
    assert newer_release_hint("0.9.3") is None
    assert not route.called


@pytest.mark.parametrize(
    "prefix, expected",
    [
        ("/home/u/.cache/uv/archive-v0/AbC123", "uvx qod@latest"),
        (r"C:\Users\u\AppData\Local\uv\cache\archive-v0\AbC", "uvx qod@latest"),
        ("/home/u/.local/share/uv/tools/qod", "uv tool upgrade qod"),
        ("/home/u/.local/share/pipx/venvs/qod", "pipx upgrade qod"),
        ("/home/u/proj/.venv", "/home/u/proj/.venv/bin/python -m pip install --upgrade qod"),
    ],
)
def test_upgrade_command_matches_the_install_method(prefix, expected):
    if "\\" in prefix and not sys.platform.startswith("win"):
        prefix = prefix.replace("\\", "/")
    assert upgrade_command(prefix, "/home/u/proj/.venv/bin/python") == expected


def test_cached_release_hint_asks_pypi_once_per_day(respx_mock, tmp_path):
    route = respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("0.9.5"))
    cache = tmp_path / "update-check.json"
    clock = [1_000_000.0]
    hint = lambda: cached_release_hint("0.9.3", cache_file=cache, now=lambda: clock[0])
    assert "0.9.5" in hint()
    clock[0] += 3600
    assert "0.9.5" in hint()
    assert route.call_count == 1
    clock[0] += 24 * 3600
    hint()
    assert route.call_count == 2


def test_cached_release_hint_offline_is_silent_and_not_retried_every_command(respx_mock, tmp_path):
    route = respx_mock.get(PYPI_URL).mock(side_effect=httpx.ConnectError("no network"))
    cache = tmp_path / "update-check.json"
    assert cached_release_hint("0.9.3", cache_file=cache, now=lambda: 1_000_000.0) is None
    assert cached_release_hint("0.9.3", cache_file=cache, now=lambda: 1_000_060.0) is None
    assert route.call_count == 1


def test_cached_release_hint_survives_a_corrupt_or_unwritable_cache(respx_mock, tmp_path):
    respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("0.9.5"))
    corrupt = tmp_path / "update-check.json"
    corrupt.write_text("{not json")
    assert "0.9.5" in cached_release_hint("0.9.3", cache_file=corrupt)
    blocker = tmp_path / "file"
    blocker.write_text("")
    assert "0.9.5" in cached_release_hint("0.9.3", cache_file=blocker / "sub" / "c.json")
