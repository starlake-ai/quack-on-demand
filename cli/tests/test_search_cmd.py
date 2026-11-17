"""qod search: each command sends its endpoint's body; --column repeats in order."""

import json

import httpx
import pytest

from qod_cli.main import app

BASE = "http://localhost:20900"


@pytest.mark.parametrize(
    "argv,path,body",
    [
        (
            ["search", "create-index", "--tenant", "acme", "--database", "lake",
             "--table", "main.pages", "--column", "title", "--column", "body"],
            "/api/search/index/create",
            {"tenant": "acme", "database": "lake", "table": "main.pages",
             "columns": ["title", "body"]},
        ),
        (
            ["search", "create-index", "--tenant", "acme", "--database", "lake",
             "--table", "pages", "--column", "body", "--column", "title", "--column", "tags"],
            "/api/search/index/create",
            {"tenant": "acme", "database": "lake", "table": "pages",
             "columns": ["body", "title", "tags"]},
        ),
        (
            ["search", "list-indexes", "--tenant", "acme"],
            "/api/search/index/list",
            {"tenant": "acme"},
        ),
        (
            ["search", "list-indexes", "--tenant", "acme", "--database", "lake"],
            "/api/search/index/list",
            {"tenant": "acme", "database": "lake"},
        ),
        (
            ["search", "drop-index", "--tenant", "acme", "--database", "lake", "--table", "pages"],
            "/api/search/index/delete",
            {"tenant": "acme", "database": "lake", "table": "pages"},
        ),
        (
            ["search", "drop-index", "--tenant", "acme", "--database", "lake",
             "--index-id", "0f0e0d0c-0b0a-4908-8706-050403020100"],
            "/api/search/index/delete",
            {"tenant": "acme", "database": "lake",
             "indexId": "0f0e0d0c-0b0a-4908-8706-050403020100"},
        ),
        (
            ["search", "build", "--tenant", "acme", "--database", "lake", "--table", "pages",
             "--pool", "bi"],
            "/api/search/index/build",
            {"tenant": "acme", "database": "lake", "table": "pages", "pool": "bi"},
        ),
    ],
)
def test_search_command_sends_its_body(runner, respx_mock, argv, path, body):
    route = respx_mock.post(f"{BASE}{path}").mock(return_value=httpx.Response(200, json={}))
    r = runner.invoke(app, argv)
    assert r.exit_code == 0, r.output
    assert json.loads(route.calls.last.request.content) == body


def test_create_index_needs_a_column(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/search/index/create").mock(
        return_value=httpx.Response(200, json={})
    )
    r = runner.invoke(
        app, ["search", "create-index", "--tenant", "acme", "--database", "lake", "--table", "pages"]
    )
    assert r.exit_code == 2
    assert not route.called


def test_create_index_has_no_k1_or_b_flag(runner):
    r = runner.invoke(app, ["search", "create-index", "--help"])
    assert r.exit_code == 0
    assert "--k1" not in r.output and "--b " not in r.output and "--options" not in r.output


@pytest.mark.parametrize(
    "extra",
    [[], ["--table", "pages", "--index-id", "0f0e0d0c-0b0a-4908-8706-050403020100"]],
)
def test_drop_index_needs_exactly_one_target(runner, respx_mock, extra):
    route = respx_mock.post(f"{BASE}/api/search/index/delete").mock(
        return_value=httpx.Response(200, json={})
    )
    r = runner.invoke(
        app, ["search", "drop-index", "--tenant", "acme", "--database", "lake", *extra]
    )
    assert r.exit_code == 2
    assert not route.called
