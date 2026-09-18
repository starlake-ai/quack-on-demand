import httpx

from qod_cli.launcher import newer_release_hint

PYPI_URL = "https://pypi.org/pypi/qod/json"


def _pypi_response(version: str) -> httpx.Response:
    return httpx.Response(200, json={"info": {"version": version}})


def test_newer_release_hint_names_both_versions_when_pypi_is_ahead(respx_mock):
    respx_mock.get(PYPI_URL).mock(return_value=_pypi_response("0.9.5"))
    hint = newer_release_hint("0.9.3")
    assert hint is not None
    assert "0.9.5" in hint
    assert "0.9.3" in hint
    assert "uvx qod@latest" in hint


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
