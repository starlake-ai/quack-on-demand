"""`qod serve --demo --s3` / `--s3-path`: the store, its flags and its teardown."""

import hashlib
import io
import os
import shutil
import sys
import tarfile
import zipfile

import httpx
import pytest
import respx

from qod_cli import launcher

# Read at import: the autouse isolated_env fixture deletes every QOD_* variable.
LIVE_WEED = os.environ.get("QOD_DEMO_WEED_BIN") or shutil.which("weed")


def _tar_with(name: str, body: bytes) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as t:
        info = tarfile.TarInfo(name)
        info.size = len(body)
        t.addfile(info, io.BytesIO(body))
    return buf.getvalue()


def _zip_with(name: str, body: bytes) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(name, body)
    return buf.getvalue()


@pytest.mark.parametrize(
    "os_name,arch,asset",
    [
        ("darwin", "arm64", "darwin_arm64.tar.gz"),
        ("darwin", "x86_64", "darwin_amd64.tar.gz"),
        ("linux", "x86_64", "linux_amd64.tar.gz"),
        ("linux", "amd64", "linux_amd64.tar.gz"),
        ("linux", "aarch64", "linux_arm64.tar.gz"),
        ("linux", "arm64", "linux_arm64.tar.gz"),
        ("win32", "amd64", "windows_amd64.zip"),
        ("win32", "x86_64", "windows_amd64.zip"),
    ],
)
def test_every_supported_platform_has_an_asset_and_a_sha256_pin(os_name, arch, asset):
    name, sha = launcher.weed_asset(os_name, arch)
    assert name == asset
    assert launcher._SHA256_RE.match(sha)


def test_windows_on_arm_has_no_build_and_says_what_to_do():
    with pytest.raises(launcher.WeedUnavailable, match="--s3-path"):
        launcher.weed_asset("win32", "arm64")


def _pin(monkeypatch, os_name, arch, name, body):
    monkeypatch.setitem(
        launcher._WEED_ASSETS, (os_name, arch), (name, hashlib.sha256(body).hexdigest())
    )


def test_ensure_weed_downloads_verifies_and_caches(monkeypatch, tmp_path):
    body = _tar_with("weed", b"#!/bin/sh\n")
    _pin(monkeypatch, "linux", "x86_64", "linux_amd64.tar.gz", body)
    url = f"{launcher._WEED_BASE_URL}/linux_amd64.tar.gz"
    with respx.mock:
        route = respx.get(url).mock(return_value=httpx.Response(200, content=body))
        exe = launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64")
        assert exe == tmp_path / "weed" / launcher.WEED_VERSION / "weed"
        assert exe.read_bytes() == b"#!/bin/sh\n"
        assert os.access(exe, os.X_OK)
        # Cached: a second call reaches no network.
        assert launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64") == exe
        assert route.call_count == 1
    assert not (exe.parent / "linux_amd64.tar.gz").exists()


def test_ensure_weed_unpacks_the_windows_zip(monkeypatch, tmp_path):
    body = _zip_with("weed.exe", b"MZ")
    _pin(monkeypatch, "win32", "amd64", "windows_amd64.zip", body)
    with respx.mock:
        respx.get(f"{launcher._WEED_BASE_URL}/windows_amd64.zip").mock(
            return_value=httpx.Response(200, content=body)
        )
        exe = launcher.ensure_weed(tmp_path, os_name="win32", arch="amd64")
    assert exe.name == "weed.exe"
    assert exe.read_bytes() == b"MZ"


def test_ensure_weed_refuses_a_zip_member_leaving_the_folder(monkeypatch, tmp_path):
    body = _zip_with("../evil", b"x")
    _pin(monkeypatch, "win32", "amd64", "windows_amd64.zip", body)
    with respx.mock:
        respx.get(f"{launcher._WEED_BASE_URL}/windows_amd64.zip").mock(
            return_value=httpx.Response(200, content=body)
        )
        with pytest.raises(RuntimeError, match="unexpected member"):
            launcher.ensure_weed(tmp_path, os_name="win32", arch="amd64")
    assert not (tmp_path / "weed" / "evil").exists()


def test_ensure_weed_refuses_a_checksum_mismatch_and_keeps_nothing(monkeypatch, tmp_path):
    monkeypatch.setitem(
        launcher._WEED_ASSETS, ("linux", "x86_64"), ("linux_amd64.tar.gz", "0" * 64)
    )
    with respx.mock:
        respx.get(f"{launcher._WEED_BASE_URL}/linux_amd64.tar.gz").mock(
            return_value=httpx.Response(200, content=_tar_with("weed", b"tampered"))
        )
        with pytest.raises(launcher.IntegrityError):
            launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64")
    folder = tmp_path / "weed" / launcher.WEED_VERSION
    assert list(folder.iterdir()) == []


def test_ensure_weed_honors_the_override_without_a_download(monkeypatch, tmp_path):
    local = tmp_path / "my-weed"
    local.write_text("")
    monkeypatch.setenv("QOD_DEMO_WEED_BIN", str(local))
    with respx.mock:
        assert launcher.ensure_weed(tmp_path / "cache") == local


def test_ensure_weed_refuses_an_override_that_is_not_a_file(monkeypatch, tmp_path):
    monkeypatch.setenv("QOD_DEMO_WEED_BIN", str(tmp_path / "missing"))
    with pytest.raises(launcher.WeedUnavailable, match="QOD_DEMO_WEED_BIN"):
        launcher.ensure_weed(tmp_path)
