"""A Windows manager's nodes run qod-node.exe, a release asset the qod CLI downloads for the jar's
release (the static Windows build is too large to bundle in the jar). Off Windows the jar bundles
qod-node and the CLI hands the manager nothing."""

import hashlib
import pathlib

import httpx
import pytest
import respx

from qod_cli import launcher

EXE = b"MZ not really an exe"
SHA = hashlib.sha256(EXE).hexdigest()


def _jar(tmp_path, name):
    jar = tmp_path / name
    jar.write_text("")
    return jar


@pytest.mark.parametrize("name,release", [
    ("quack-on-demand-assembly-0.11.0.jar", "v0.11.0"),
    ("quack-on-demand-assembly-0.11.0-SNAPSHOT.jar", "snapshot"),
    ("my-build.jar", "snapshot"),
])
def test_node_release_follows_the_jar(name, release):
    assert launcher.node_release(pathlib.Path(name)) == release


def test_qod_node_exe_url():
    assert launcher.qod_node_exe_url("v0.11.0", "x86_64") == (
        "https://github.com/starlake-ai/quack-on-demand/releases/download/v0.11.0/"
        "qod-node-windows-x86_64.exe"
    )


@respx.mock
def test_a_release_exe_is_downloaded_checked_and_then_reused(tmp_path):
    url = launcher.qod_node_exe_url("v0.11.0", "aarch64")
    respx.get(url + ".sha256").mock(return_value=httpx.Response(200, text=f"{SHA}  qod-node-windows-aarch64.exe\n"))
    route = respx.get(url).mock(return_value=httpx.Response(200, content=EXE))
    jar = _jar(tmp_path, "quack-on-demand-assembly-0.11.0.jar")
    exe = launcher.ensure_windows_qod_node(jar, tmp_path / "cache", arch="ARM64")
    assert exe == tmp_path / "cache" / "qod-node" / "v0.11.0" / "qod-node.exe"
    assert exe.read_bytes() == EXE
    assert route.call_count == 1
    # A release never changes: once cached it is reused without asking the network.
    with respx.mock:
        assert launcher.ensure_windows_qod_node(jar, tmp_path / "cache", arch="ARM64") == exe


@respx.mock
def test_a_download_that_fails_its_digest_is_refused(tmp_path):
    url = launcher.qod_node_exe_url("v0.11.0", "x86_64")
    respx.get(url + ".sha256").mock(return_value=httpx.Response(200, text="0" * 64))
    respx.get(url).mock(return_value=httpx.Response(200, content=EXE))
    jar = _jar(tmp_path, "quack-on-demand-assembly-0.11.0.jar")
    with pytest.raises(launcher.IntegrityError):
        launcher.ensure_windows_qod_node(jar, tmp_path / "cache", arch="AMD64")
    assert not (tmp_path / "cache" / "qod-node" / "v0.11.0" / "qod-node.exe").exists()


@respx.mock
def test_the_snapshot_exe_is_rechecked_against_its_published_digest(tmp_path):
    url = launcher.qod_node_exe_url("snapshot", "x86_64")
    jar = _jar(tmp_path, "quack-on-demand-assembly-snapshot.jar")
    respx.get(url + ".sha256").mock(return_value=httpx.Response(200, text=SHA))
    route = respx.get(url).mock(return_value=httpx.Response(200, content=EXE))
    exe = launcher.ensure_windows_qod_node(jar, tmp_path / "cache", arch="AMD64")
    assert route.call_count == 1
    # Same digest: reused. A new snapshot: downloaded again.
    launcher.ensure_windows_qod_node(jar, tmp_path / "cache", arch="AMD64")
    assert route.call_count == 1
    newer = b"MZ newer"
    respx.get(url + ".sha256").mock(return_value=httpx.Response(200, text=hashlib.sha256(newer).hexdigest()))
    respx.get(url).mock(return_value=httpx.Response(200, content=newer))
    assert launcher.ensure_windows_qod_node(jar, tmp_path / "cache", arch="AMD64") == exe
    assert exe.read_bytes() == newer


def test_node_binary_is_none_off_windows_or_when_qod_node_bin_is_set(tmp_path):
    jar = _jar(tmp_path, "quack-on-demand-assembly-0.11.0.jar")
    with respx.mock:  # no routes: neither case may touch the network
        assert launcher.node_binary(jar, tmp_path, os_name="linux", environ={}) is None
        assert launcher.node_binary(jar, tmp_path, os_name="win32", environ={"QOD_NODE_BIN": "C:\\q.exe"}) is None


@respx.mock
def test_node_binary_names_qod_node_bin_when_the_download_fails(tmp_path):
    url = launcher.qod_node_exe_url("v0.11.0", "x86_64")
    respx.get(url + ".sha256").mock(return_value=httpx.Response(404))
    jar = _jar(tmp_path, "quack-on-demand-assembly-0.11.0.jar")
    with pytest.raises(RuntimeError, match="QOD_NODE_BIN"):
        launcher.node_binary(jar, tmp_path, os_name="win32", environ={})


def test_runtime_env_hands_the_manager_the_provisioned_qod_node(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin"},
        duckdb_bin=tmp_path / "bin",
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=tmp_path / "lib",
        os_name="win32",
        qod_node_bin=tmp_path / "qod-node.exe",
    )
    assert env["QOD_NODE_BIN"] == str(tmp_path / "qod-node.exe")
