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
