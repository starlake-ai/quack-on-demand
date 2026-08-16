import os
import stat

import pytest

from qod_cli.config import Settings, config_path, load_settings, save_profile


def test_defaults_when_no_file_and_no_env():
    st = load_settings()
    assert st.manager_url == "http://localhost:20900"
    assert st.edge_port == 31338
    assert st.edge_tls is True
    assert st.edge_tls_verify is False


def test_save_then_load_roundtrip():
    save_profile("default", {"manager_url": "http://mgr:1", "token": "t1", "edge_port": 999})
    st = load_settings()
    assert st.manager_url == "http://mgr:1"
    assert st.token == "t1"
    assert st.edge_port == 999


def test_named_profile_isolated():
    save_profile("default", {"token": "dev"})
    save_profile("prod", {"token": "prod", "manager_url": "http://prod:20900"})
    assert load_settings("prod").token == "prod"
    assert load_settings("default").token == "dev"
    assert load_settings("default").manager_url == "http://localhost:20900"


def test_env_overrides_profile(monkeypatch):
    save_profile("default", {"manager_url": "http://file:1", "edge_tls": True})
    monkeypatch.setenv("QOD_MANAGER_URL", "http://env:2")
    monkeypatch.setenv("QOD_TLS", "false")
    st = load_settings()
    assert st.manager_url == "http://env:2"
    assert st.edge_tls is False


def test_overrides_beat_env(monkeypatch):
    monkeypatch.setenv("QOD_MANAGER_URL", "http://env:2")
    st = load_settings(overrides={"manager_url": "http://flag:3"})
    assert st.manager_url == "http://flag:3"


@pytest.mark.skipif(os.name == "nt", reason="POSIX file modes not applicable on Windows")
def test_file_mode_is_0600():
    save_profile("default", {"token": "secret"})
    mode = stat.S_IMODE(config_path().stat().st_mode)
    assert mode == 0o600


def test_save_merges_existing_keys():
    save_profile("default", {"token": "t1", "manager_url": "http://a:1"})
    save_profile("default", {"token": "t2"})
    st = load_settings()
    assert st.token == "t2"
    assert st.manager_url == "http://a:1"


def test_sticky_default_profile_roundtrip():
    from qod_cli.config import default_profile, set_default_profile

    assert default_profile() == "default"
    save_profile("prod", {"manager_url": "http://prod:20900"})
    set_default_profile("prod")
    assert default_profile() == "prod"
    # the sticky pointer must not clobber stored profiles
    assert load_settings("prod").manager_url == "http://prod:20900"


def test_sticky_default_drives_resolution_and_flag_env_win(runner):
    from typer.testing import CliRunner

    from qod_cli.config import set_default_profile
    from qod_cli.main import app

    save_profile("prod", {"manager_url": "http://prod:20900"})
    save_profile("staging", {"manager_url": "http://staging:20900"})
    set_default_profile("prod")
    r = CliRunner().invoke(app, ["--json", "config", "profiles"])
    assert r.exit_code == 0, r.output
    import json as _json

    rows = {row["profile"]: row for row in _json.loads(r.stdout)}
    assert rows["prod"]["active"] is True and rows["prod"]["default"] is True
    # explicit flag wins over the sticky default
    r = CliRunner().invoke(app, ["--profile", "staging", "--json", "config", "profiles"])
    rows = {row["profile"]: row for row in _json.loads(r.stdout)}
    assert rows["staging"]["active"] is True and rows["prod"]["default"] is True
    # env wins over sticky too
    import os as _os

    _os.environ["QOD_PROFILE"] = "staging"
    try:
        r = CliRunner().invoke(app, ["--json", "config", "profiles"])
        rows = {row["profile"]: row for row in _json.loads(r.stdout)}
        assert rows["staging"]["active"] is True
    finally:
        del _os.environ["QOD_PROFILE"]


def test_config_use_refuses_unknown_profile():
    from typer.testing import CliRunner

    from qod_cli.config import default_profile
    from qod_cli.main import app

    r = CliRunner().invoke(app, ["config", "use", "nope"])
    assert r.exit_code == 1
    assert "unknown profile" in r.output
    assert default_profile() == "default"
