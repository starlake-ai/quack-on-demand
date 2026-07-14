import json

from qod_cli.output import render


def test_json_mode(capsys):
    render({"a": 1}, as_json=True)
    assert json.loads(capsys.readouterr().out) == {"a": 1}


def test_none_prints_ok(capsys):
    render(None, as_json=False)
    assert capsys.readouterr().out.strip() == "ok"


def test_string_prints_raw(capsys):
    render("plain text", as_json=False)
    assert capsys.readouterr().out.strip() == "plain text"


def test_list_of_dicts_prints_table(capsys):
    render([{"id": "t1", "name": "acme"}, {"id": "t2", "name": "globex"}], as_json=False)
    out = capsys.readouterr().out
    assert "acme" in out and "globex" in out and "id" in out


def test_single_list_key_unwrapped(capsys):
    render({"tenants": [{"id": "t1"}]}, as_json=False)
    assert "t1" in capsys.readouterr().out


def test_plain_dict_prints_key_value(capsys):
    render({"username": "admin", "superuser": True}, as_json=False)
    out = capsys.readouterr().out
    assert "username" in out and "admin" in out


def test_nested_values_json_encoded(capsys):
    render([{"id": "x", "cfg": {"k": "v"}}], as_json=False)
    assert '{"k": "v"}' in capsys.readouterr().out
