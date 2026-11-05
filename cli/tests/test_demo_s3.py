"""`qod serve --demo --s3` / `--s3-path`: the store, its flags and its teardown."""

import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

import httpx
import pytest
import respx
import typer

from qod_cli import demo_s3, launcher

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


def test_ensure_weed_leaves_no_binary_behind_when_the_unpack_fails(monkeypatch, tmp_path):
    body = _tar_with("weed", b"#!/bin/sh\n")
    _pin(monkeypatch, "linux", "x86_64", "linux_amd64.tar.gz", body)
    real_extract = launcher._extract_tar_gz

    def partial(archive, dest):
        (dest / "weed").write_bytes(b"trunc")
        raise OSError("disk full")

    folder = tmp_path / "weed" / launcher.WEED_VERSION
    with respx.mock:
        route = respx.get(f"{launcher._WEED_BASE_URL}/linux_amd64.tar.gz").mock(
            return_value=httpx.Response(200, content=body)
        )
        monkeypatch.setattr(launcher, "_extract_tar_gz", partial)
        with pytest.raises(OSError, match="disk full"):
            launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64")
        assert not (folder / "weed").exists()
        assert not list(folder.glob(".partial-*"))
        assert list(folder.iterdir()) == []
        # A later call starts over instead of reusing the truncated file.
        monkeypatch.setattr(launcher, "_extract_tar_gz", real_extract)
        exe = launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64")
        assert route.call_count == 2
    assert exe == folder / "weed"
    assert exe.read_bytes() == b"#!/bin/sh\n"
    assert os.access(exe, os.X_OK)
    assert not list(folder.glob(".partial-*"))


def test_ensure_weed_names_the_missing_binary_and_keeps_nothing(monkeypatch, tmp_path):
    body = _tar_with("bin/weed", b"#!/bin/sh\n")
    _pin(monkeypatch, "linux", "x86_64", "linux_amd64.tar.gz", body)
    with respx.mock:
        respx.get(f"{launcher._WEED_BASE_URL}/linux_amd64.tar.gz").mock(
            return_value=httpx.Response(200, content=body)
        )
        with pytest.raises(RuntimeError, match="weed"):
            launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64")
    folder = tmp_path / "weed" / launcher.WEED_VERSION
    assert not (folder / "weed").exists()
    assert list(folder.iterdir()) == []


def test_ensure_weed_refuses_a_tar_member_leaving_the_folder(monkeypatch, tmp_path):
    body = _tar_with("../evil", b"x")
    _pin(monkeypatch, "linux", "x86_64", "linux_amd64.tar.gz", body)
    with respx.mock:
        respx.get(f"{launcher._WEED_BASE_URL}/linux_amd64.tar.gz").mock(
            return_value=httpx.Response(200, content=body)
        )
        with pytest.raises(RuntimeError, match="unexpected member"):
            launcher.ensure_weed(tmp_path, os_name="linux", arch="x86_64")
    assert not list(tmp_path.rglob("evil"))


def test_ensure_weed_lowercases_the_machine_name(monkeypatch, tmp_path):
    body = _zip_with("weed.exe", b"MZ")
    _pin(monkeypatch, "win32", "amd64", "windows_amd64.zip", body)
    with respx.mock:
        respx.get(f"{launcher._WEED_BASE_URL}/windows_amd64.zip").mock(
            return_value=httpx.Response(200, content=body)
        )
        exe = launcher.ensure_weed(tmp_path, os_name="win32", arch="AMD64")
    assert exe.name == "weed.exe"


ARGS = dict(
    demo=True, s3=False, s3_path=None, endpoint=None, region=None,
    access_key_id=None, secret_access_key=None, environ={},
)


def _req(**over):
    return demo_s3.request(**{**ARGS, **over})


def _own(**over):
    return _req(s3_path="s3://my-bucket/qod", access_key_id="K", secret_access_key="S", **over)


def test_plain_demo_and_plain_serve_have_no_request():
    assert _req() is None
    assert _req(demo=False) is None
    # Not a demo: serve's own target flags stay serve's.
    assert _req(demo=False, endpoint="http://x:1", access_key_id="K", secret_access_key="S") is None


@pytest.mark.parametrize("over", [{"s3": True}, {"s3_path": "s3://b12/x"}])
def test_s3_flags_need_demo(over):
    with pytest.raises(demo_s3.DemoS3Error, match="--demo"):
        _req(demo=False, **over)


def test_s3_and_s3_path_exclude_each_other():
    with pytest.raises(demo_s3.DemoS3Error, match="--s3-path"):
        _req(s3=True, s3_path="s3://b12/x", access_key_id="K", secret_access_key="S")


@pytest.mark.parametrize(
    "over",
    [
        {"endpoint": "http://x:1"},
        {"region": "eu-west-1"},
        {"access_key_id": "K"},
        {"secret_access_key": "S"},
        {"s3": True, "region": "eu-west-1"},
    ],
)
def test_store_flags_need_s3_path(over):
    with pytest.raises(demo_s3.DemoS3Error, match="--s3-path"):
        _req(**over)


def test_s3_alone_asks_for_the_local_store():
    assert isinstance(_req(s3=True), demo_s3.LocalRequest)


@pytest.mark.parametrize(
    "path",
    ["my-bucket/x", "gs://b12/x", "az://b12/x", "r2://b12/x", "s3a://b12/x", "s3://", "s3://B/x",
     "s3://b12/it's", "s3://b12/../x", "s3://b12/a b"],
)
def test_s3_path_refuses_what_is_not_a_plain_s3_url(path):
    with pytest.raises(demo_s3.DemoS3Error, match="s3://"):
        _req(s3_path=path, access_key_id="K", secret_access_key="S")


def test_s3_path_keeps_a_bucket_root_and_drops_a_trailing_slash():
    assert _req(s3_path="s3://b12", access_key_id="K", secret_access_key="S").path == "s3://b12"
    assert _req(s3_path="s3://b12/a/", access_key_id="K", secret_access_key="S").path == "s3://b12/a"


def test_keys_come_from_flags_then_aws_env_and_never_qod_s3():
    env = {"AWS_ACCESS_KEY_ID": "EK", "AWS_SECRET_ACCESS_KEY": "ES"}
    r = _req(s3_path="s3://b12/x", environ=env)
    assert (r.access_key_id, r.secret_access_key) == ("EK", "ES")
    r = _req(s3_path="s3://b12/x", access_key_id="K", secret_access_key="S", environ=env)
    assert (r.access_key_id, r.secret_access_key) == ("K", "S")
    with pytest.raises(demo_s3.DemoS3Error, match="key"):
        _req(s3_path="s3://b12/x", environ={"QOD_S3_ACCESS_KEY_ID": "A",
                                            "QOD_S3_SECRET_ACCESS_KEY": "B"})


def test_temporary_aws_keys_are_refused_with_the_reason():
    env = {"AWS_ACCESS_KEY_ID": "EK", "AWS_SECRET_ACCESS_KEY": "ES", "AWS_SESSION_TOKEN": "T"}
    with pytest.raises(demo_s3.DemoS3Error, match="long-lived"):
        _req(s3_path="s3://b12/x", environ=env)
    # Keys given as flags are not the token's.
    assert _req(s3_path="s3://b12/x", access_key_id="K", secret_access_key="S", environ=env)


def test_region_comes_from_flag_then_aws_env_then_a_default():
    assert _own().region == "us-east-1"
    assert _own(environ={"AWS_DEFAULT_REGION": "d"}).region == "d"
    assert _own(environ={"AWS_REGION": "r", "AWS_DEFAULT_REGION": "d"}).region == "r"
    assert _own(region="f", environ={"AWS_REGION": "r"}).region == "f"


@pytest.mark.parametrize(
    "endpoint,host,ssl,style",
    [
        (None, "", True, "vhost"),
        ("http://minio:9000", "minio:9000", False, "path"),
        ("https://acct.r2.example/", "acct.r2.example", True, "path"),
        ("minio:9000", "minio:9000", True, "path"),
    ],
)
def test_endpoint_decides_scheme_and_url_style(endpoint, host, ssl, style):
    r = _own(endpoint=endpoint)
    assert (r.endpoint, r.use_ssl, r.url_style) == (host, ssl, style)


def test_endpoint_with_a_path_is_refused():
    with pytest.raises(demo_s3.DemoS3Error, match="--endpoint"):
        _own(endpoint="http://minio:9000/some/path")


def test_a_refused_endpoint_is_not_echoed():
    with pytest.raises(demo_s3.DemoS3Error, match="--endpoint") as e:
        _own(endpoint="http://user:secret@h:1/x")
    assert "secret" not in str(e.value)
    assert "user" not in str(e.value)


@pytest.mark.parametrize(
    "over", [{"access_key_id": "K"}, {"secret_access_key": "S"}], ids=["id-only", "secret-only"]
)
def test_half_a_key_pair_is_refused_even_when_the_env_has_the_other_half(over):
    env = {"AWS_ACCESS_KEY_ID": "EK", "AWS_SECRET_ACCESS_KEY": "ES"}
    with pytest.raises(demo_s3.DemoS3Error, match="both --access-key-id and --secret-access-key"):
        _req(s3_path="s3://b12/x", environ=env, **over)


def test_a_request_prints_no_secret():
    req = _req(s3_path="s3://b12/x", access_key_id="HIDDENKEY", secret_access_key="HIDDENSECRET")
    assert "HIDDEN" not in repr(req)


def test_bucket_env_sets_every_variable_and_drops_ambient_values():
    base = {
        "QOD_S3_ENDPOINT": "stale:1", "QOD_DEMO_S3_LOCAL": "1", "QOD_S3_ACCESS_KEY_ID": "stale",
        "PATH": "/bin",
    }
    env = demo_s3.bucket_env(base, _own(), run_id="1a2b3c4d")
    assert env["QOD_DEMO_S3_PATH"] == "s3://my-bucket/qod/qod-demo-1a2b3c4d"
    assert "QOD_DEMO_S3_LOCAL" not in env
    assert "QOD_S3_ENDPOINT" not in env
    assert env["QOD_S3_ACCESS_KEY_ID"] == "K"
    assert env["QOD_S3_SECRET_ACCESS_KEY"] == "S"
    assert env["QOD_S3_REGION"] == "us-east-1"
    assert env["QOD_S3_URL_STYLE"] == "vhost"
    assert env["QOD_S3_USE_SSL"] == "true"
    assert env["PATH"] == "/bin"
    # The caller's dict is untouched.
    assert base["QOD_S3_ENDPOINT"] == "stale:1"


def test_bucket_env_makes_a_new_run_folder_each_time():
    a = demo_s3.bucket_env({}, _own())["QOD_DEMO_S3_PATH"]
    b = demo_s3.bucket_env({}, _own())["QOD_DEMO_S3_PATH"]
    assert a != b
    assert re.fullmatch(r"s3://my-bucket/qod/qod-demo-[0-9a-f]{8}", a)


class _FakeStore:
    s3_port = 18333
    access_key_id = "LK"
    secret_access_key = "LS"


def test_local_env_points_at_the_store_and_exempts_it_from_a_proxy():
    env = demo_s3.local_env(
        {"QOD_S3_ENDPOINT": "stale:1", "NO_PROXY": "corp"}, _FakeStore(), run_id="1a2b3c4d"
    )
    assert env["QOD_DEMO_S3_PATH"] == "s3://qod-demo/qod-demo-1a2b3c4d"
    assert env["QOD_DEMO_S3_LOCAL"] == "1"
    assert env["QOD_S3_ENDPOINT"] == "127.0.0.1:18333"
    assert env["QOD_S3_ACCESS_KEY_ID"] == "LK"
    assert env["QOD_S3_SECRET_ACCESS_KEY"] == "LS"
    assert env["QOD_S3_REGION"] == "us-east-1"
    assert env["QOD_S3_URL_STYLE"] == "path"
    assert env["QOD_S3_USE_SSL"] == "false"
    assert env["NO_PROXY"] == "127.0.0.1,corp"
    assert env["no_proxy"] == "127.0.0.1"


def test_plain_env_drops_a_stale_demo_path():
    env = demo_s3.plain_env({"QOD_DEMO_S3_PATH": "s3://x", "QOD_DEMO_S3_LOCAL": "1", "PATH": "/b"})
    assert env == {"PATH": "/b"}


def test_weed_command_binds_loopback_and_disables_what_the_demo_does_not_use(tmp_path):
    ports = dict(zip(demo_s3.PORT_NAMES, range(40001, 40009)))
    assert demo_s3.weed_command(tmp_path / "weed", tmp_path / "f", ports) == [
        str(tmp_path / "weed"),
        f"-logdir={tmp_path / 'f' / 'logs'}",
        "server",
        f"-dir={tmp_path / 'f' / 'data'}",
        "-ip=127.0.0.1",
        "-ip.bind=127.0.0.1",
        "-master.port=40001",
        "-master.port.grpc=40002",
        "-volume.port=40003",
        "-volume.port.grpc=40004",
        "-filer.port=40005",
        "-filer.port.grpc=40006",
        "-s3",
        "-s3.port=40007",
        "-s3.port.grpc=40008",
        f"-s3.config={tmp_path / 'f' / 's3.json'}",
        "-s3.port.iceberg=0",
        "-s3.port.lance=0",
        "-master.volumeSizeLimitMB=1024",
    ]


class _FakeProc:
    def __init__(self, exits_at=None):
        self.pid = 4242
        self.killed = 0
        self._code = exits_at

    def poll(self):
        return self._code

    def kill(self):
        self.killed += 1
        self._code = -9

    def wait(self, timeout=None):
        return self._code


def _start(tmp_path, monkeypatch, proc, answers):
    monkeypatch.setattr(demo_s3.tempfile, "tempdir", str(tmp_path))
    # On win32 _discard and stop() taskkill the proc's pid: no test may signal a real process.
    monkeypatch.setattr(demo_s3, "_kill_tree", lambda pid: None)
    captured = {}

    def popen(cmd, **kw):
        captured["cmd"], captured["kw"] = cmd, kw
        return proc

    clock = {"t": 0.0}

    def monotonic():
        clock["t"] += 1.0
        return clock["t"]

    store = demo_s3.LocalStore.start(
        tmp_path / "weed", record=tmp_path / "rec" / "store.json",
        popen=popen, answers=answers, monotonic=monotonic, sleep=lambda s: None,
    )
    return store, captured


def _folders(tmp_path):
    return [p for p in tmp_path.iterdir() if p.name.startswith("qod-demo-s3-")]


def test_store_start_writes_private_credentials_and_a_record(tmp_path, monkeypatch):
    for name in ("AWS_ACCESS_KEY_ID", "QOD_X", "WEED_X"):
        monkeypatch.setenv(name, "ambient")
    store, captured = _start(tmp_path, monkeypatch, _FakeProc(), lambda port: True)
    folder = _folders(tmp_path)[0]
    assert stat.S_IMODE(folder.stat().st_mode) == 0o700 or sys.platform == "win32"
    config = folder / "s3.json"
    assert stat.S_IMODE(config.stat().st_mode) == 0o600 or sys.platform == "win32"
    identity = json.loads(config.read_text())["identities"][0]
    assert identity["credentials"][0]["accessKey"] == store.access_key_id
    assert identity["credentials"][0]["secretKey"] == store.secret_access_key
    assert len(store.secret_access_key) >= 32
    record = json.loads((tmp_path / "rec" / "store.json").read_text())
    assert record == {"pid": 4242, "dir": str(folder), "owner": os.getpid()}
    # Nothing secret on the command line or in the store's environment.
    assert store.secret_access_key not in " ".join(captured["cmd"])
    assert not any(k.startswith(("AWS_", "QOD_", "WEED_")) for k in captured["kw"]["env"])
    assert "PATH" in captured["kw"]["env"]
    # Its own folder, so it never reads a security.toml or master.toml from the caller's.
    assert captured["kw"]["cwd"] == folder
    assert captured["kw"].get("start_new_session") is True or sys.platform == "win32"
    assert store.secret_access_key not in repr(store)


def test_store_gives_up_after_60s_with_its_log_and_leaves_nothing(tmp_path, monkeypatch):
    proc = _FakeProc()
    with pytest.raises(demo_s3.DemoS3Error, match="did not answer"):
        _start(tmp_path, monkeypatch, proc, lambda port: False)
    assert proc.killed == 1
    assert _folders(tmp_path) == []
    assert not (tmp_path / "rec" / "store.json").exists()


def test_store_that_dies_at_start_fails_at_once(tmp_path, monkeypatch):
    with pytest.raises(demo_s3.DemoS3Error, match="exited"):
        _start(tmp_path, monkeypatch, _FakeProc(exits_at=1), lambda port: False)
    assert _folders(tmp_path) == []


def test_store_leaves_nothing_when_the_process_cannot_be_started(tmp_path, monkeypatch):
    monkeypatch.setattr(demo_s3.tempfile, "tempdir", str(tmp_path))

    def popen(cmd, **kw):
        raise OSError("exec format error")

    with pytest.raises(OSError, match="exec format error"):
        demo_s3.LocalStore.start(
            tmp_path / "weed", record=tmp_path / "rec" / "store.json", popen=popen
        )
    assert _folders(tmp_path) == []
    assert not (tmp_path / "rec" / "store.json").exists()


def test_store_kills_the_process_when_the_record_cannot_be_written(tmp_path, monkeypatch):
    (tmp_path / "rec").write_text("a file where the record's folder should be")
    proc = _FakeProc()
    with pytest.raises(OSError):
        _start(tmp_path, monkeypatch, proc, lambda port: True)
    assert proc.killed == 1
    assert _folders(tmp_path) == []


def test_store_is_cleaned_up_on_ctrl_c_while_waiting(tmp_path, monkeypatch):
    proc = _FakeProc()

    def interrupted(port):
        raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        _start(tmp_path, monkeypatch, proc, interrupted)
    assert proc.killed == 1
    assert _folders(tmp_path) == []
    assert not (tmp_path / "rec" / "store.json").exists()


def test_store_stop_keeps_a_record_another_run_has_taken_over(tmp_path, monkeypatch):
    proc = _FakeProc()
    store, _ = _start(tmp_path, monkeypatch, proc, lambda port: True)
    rec = tmp_path / "rec" / "store.json"
    rec.write_text(json.dumps({"pid": 9999, "dir": "/elsewhere/qod-demo-s3-x", "owner": 1}))
    store.stop()
    assert proc.killed >= 1
    assert _folders(tmp_path) == []
    assert json.loads(rec.read_text())["pid"] == 9999


def test_store_stop_leaves_a_broken_record_as_it_is(tmp_path, monkeypatch):
    store, _ = _start(tmp_path, monkeypatch, _FakeProc(), lambda port: True)
    rec = tmp_path / "rec" / "store.json"
    rec.write_text("not json")
    store.stop()
    assert rec.read_text() == "not json"


def test_store_stop_kills_cleans_and_can_run_twice(tmp_path, monkeypatch):
    proc = _FakeProc()
    store, _ = _start(tmp_path, monkeypatch, proc, lambda port: True)
    store.stop()
    store.stop()
    assert proc.killed >= 1
    assert _folders(tmp_path) == []
    assert not (tmp_path / "rec" / "store.json").exists()


def _record(tmp_path, pid=111, owner=222, folder=None):
    folder = folder or tmp_path / "qod-demo-s3-abc"
    folder.mkdir(exist_ok=True)
    rec = tmp_path / "store.json"
    rec.write_text(json.dumps({"pid": pid, "dir": str(folder), "owner": owner}))
    return rec, folder


def _sweep(rec, lines):
    killed = []
    demo_s3.sweep_orphan(
        record=rec, command_line=lambda pid: lines.get(pid), kill=killed.append, echo=lambda m: None
    )
    return killed


def test_sweep_kills_an_orphan_and_cleans_up(tmp_path):
    rec, folder = _record(tmp_path)
    assert _sweep(rec, {111: f"/x/weed server -dir={folder}/data"}) == [111]
    assert not folder.exists() and not rec.exists()


def test_sweep_leaves_a_store_whose_owner_lives(tmp_path):
    rec, folder = _record(tmp_path)
    line = {111: f"/x/weed server -dir={folder}/data", 222: "python qod serve --demo --s3"}
    assert _sweep(rec, line) == []
    assert folder.exists() and rec.exists()


@pytest.mark.parametrize("line", ["/bin/some-other-thing", "/x/weed server -dir=/elsewhere/data"])
def test_sweep_never_signals_a_recycled_pid(tmp_path, line):
    rec, folder = _record(tmp_path)
    assert _sweep(rec, {111: line}) == []
    assert not rec.exists()


def test_sweep_cleans_up_when_the_store_is_already_gone(tmp_path):
    rec, folder = _record(tmp_path)
    assert _sweep(rec, {}) == []
    assert not folder.exists() and not rec.exists()


def test_sweep_touches_nothing_when_the_owner_cannot_be_read(tmp_path):
    rec, folder = _record(tmp_path)
    line = {111: f"/x/weed server -dir={folder}/data", 222: demo_s3.UNKNOWN}
    assert _sweep(rec, line) == []
    assert folder.exists() and rec.exists()


def test_sweep_touches_nothing_when_the_store_cannot_be_read(tmp_path):
    rec, folder = _record(tmp_path)
    assert _sweep(rec, {111: demo_s3.UNKNOWN}) == []
    assert folder.exists() and rec.exists()


def _ps(monkeypatch, platform, result):
    monkeypatch.setattr(demo_s3.sys, "platform", platform)

    def run(cmd, **kw):
        if isinstance(result, BaseException):
            raise result
        return subprocess.CompletedProcess(cmd, result[0], stdout=result[1], stderr="")

    monkeypatch.setattr(demo_s3.subprocess, "run", run)


@pytest.mark.parametrize("platform", ["linux", "win32"])
def test_command_line_returns_the_line_of_a_live_process(monkeypatch, platform):
    _ps(monkeypatch, platform, (0, "/x/weed server\n"))
    assert demo_s3._command_line(1) == "/x/weed server"


def test_command_line_is_none_when_ps_finds_no_such_process(monkeypatch):
    _ps(monkeypatch, "linux", (1, ""))
    assert demo_s3._command_line(1) is None


def test_command_line_is_none_when_powershell_finds_no_such_process(monkeypatch):
    _ps(monkeypatch, "win32", (0, ""))
    assert demo_s3._command_line(1) is None


@pytest.mark.parametrize("platform", ["linux", "win32"])
@pytest.mark.parametrize(
    "result",
    [subprocess.TimeoutExpired("ps", 10), OSError("no ps"), (2, ""), (127, "")],
    ids=["timeout", "oserror", "rc2", "rc127"],
)
def test_command_line_is_unknown_when_the_query_cannot_answer(monkeypatch, platform, result):
    _ps(monkeypatch, platform, result)
    assert demo_s3._command_line(1) is demo_s3.UNKNOWN


def test_sweep_deletes_only_a_folder_named_as_a_store(tmp_path):
    other = tmp_path / "precious"
    rec, _ = _record(tmp_path, folder=other)
    assert _sweep(rec, {}) == []
    assert other.exists() and not rec.exists()


def test_sweep_survives_no_record_and_a_broken_one(tmp_path):
    rec = tmp_path / "store.json"
    assert _sweep(rec, {}) == []
    rec.write_text("not json")
    assert _sweep(rec, {}) == []
    assert not rec.exists()


@pytest.mark.skipif(not LIVE_WEED, reason="no weed: set QOD_DEMO_WEED_BIN or put one on PATH")
def test_live_store_starts_refuses_anonymous_and_stops_clean(tmp_path, monkeypatch):
    monkeypatch.setattr(demo_s3.tempfile, "tempdir", str(tmp_path))
    store = demo_s3.LocalStore.start(Path(LIVE_WEED), record=tmp_path / "store.json")
    try:
        r = httpx.get(f"http://127.0.0.1:{store.s3_port}/", timeout=5, trust_env=False)
        assert r.status_code == 403
        folder = _folders(tmp_path)[0]
        assert any((folder / "logs").iterdir())
    finally:
        store.stop()
    assert _folders(tmp_path) == []
    assert not (tmp_path / "store.json").exists()


class _RecordingStore:
    s3_port = 18333
    access_key_id = "LK"
    secret_access_key = "LS"

    def __init__(self):
        self.stopped = 0

    def stop(self):
        self.stopped += 1


@pytest.fixture
def demo_world(monkeypatch, tmp_path):
    """The real run_demo with provisioning stubbed and the jar launch captured."""
    from qod_cli.commands import demo as demo_cmd

    # run_demo may set FORCE_AFTER in os.environ. A bare delenv of an absent variable records
    # nothing to restore, so set it first: teardown then removes whatever the test left.
    monkeypatch.setenv("FORCE_AFTER", "placeholder")
    monkeypatch.delenv("FORCE_AFTER")
    monkeypatch.setattr(demo_cmd, "resolve_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "default_cache_dir", lambda: tmp_path / "cache")
    monkeypatch.setattr(launcher, "ensure_duckdb_cli", lambda c: tmp_path / "b")
    monkeypatch.setattr(launcher, "ensure_libduckdb", lambda c: tmp_path / "v" / "lib")
    monkeypatch.setattr(launcher, "node_binary", lambda *a, **k: None)
    monkeypatch.setattr(
        launcher, "materialize_spawn_scripts", lambda d: (tmp_path / "s.sh", tmp_path / "s.ps1")
    )
    world = {"tmp": tmp_path, "store": _RecordingStore(), "events": []}

    def fake_exec(cmd, env):
        world["cmd"], world["env"] = cmd, env
        world["events"].append("jar")

    monkeypatch.setattr(demo_cmd, "_exec", fake_exec)
    monkeypatch.setattr(launcher, "ensure_weed", lambda c: tmp_path / "weed")
    monkeypatch.setattr(demo_s3, "sweep_orphan", lambda: world["events"].append("sweep"))

    def fake_start(weed):
        world["events"].append("start")
        return world["store"]

    monkeypatch.setattr(demo_s3.LocalStore, "start", staticmethod(fake_start))
    return world


class _Ctx:
    args: list = []


def _run_demo(world, s3):
    from qod_cli.commands import demo as demo_cmd

    jar = world["tmp"] / "qod.jar"
    jar.write_text("")
    demo_cmd.run_demo(_Ctx(), None, jar, s3=s3)


def test_local_demo_sweeps_starts_runs_the_jar_and_stops_the_store(demo_world):
    _run_demo(demo_world, demo_s3.LocalRequest())
    assert demo_world["events"] == ["sweep", "start", "jar"]
    assert demo_world["store"].stopped == 1
    env = demo_world["env"]
    assert env["QOD_DEMO_S3_LOCAL"] == "1"
    assert env["QOD_S3_ENDPOINT"] == "127.0.0.1:18333"


def test_local_store_is_stopped_when_the_jar_fails(demo_world, monkeypatch):
    from qod_cli.commands import demo as demo_cmd

    def failing_exec(cmd, env):
        raise typer.Exit(1)

    monkeypatch.setattr(demo_cmd, "_exec", failing_exec)
    with pytest.raises(typer.Exit):
        _run_demo(demo_world, demo_s3.LocalRequest())
    assert demo_world["store"].stopped == 1


def test_own_bucket_demo_starts_no_store(demo_world):
    _run_demo(demo_world, _own())
    assert demo_world["events"] == ["jar"]
    env = demo_world["env"]
    assert env["QOD_DEMO_S3_PATH"].startswith("s3://my-bucket/qod/qod-demo-")
    assert "QOD_DEMO_S3_LOCAL" not in env


def test_plain_demo_drops_a_stale_path_and_starts_no_store(demo_world, monkeypatch):
    monkeypatch.setenv("QOD_DEMO_S3_PATH", "s3://stale/qod-demo-1a2b3c4d")
    _run_demo(demo_world, None)
    assert demo_world["events"] == ["jar"]
    assert "QOD_DEMO_S3_PATH" not in demo_world["env"]


@pytest.mark.parametrize(
    "error",
    [
        launcher.WeedUnavailable("no build; use --s3-path"),
        launcher.IntegrityError("bad sha256"),
        demo_s3.DemoS3Error("store did not answer"),
        httpx.ConnectError("offline"),
    ],
)
def test_a_failed_store_stops_the_demo_before_the_jar(demo_world, monkeypatch, capsys, error):
    def failing(c):
        raise error

    monkeypatch.setattr(launcher, "ensure_weed", failing)
    with pytest.raises(typer.Exit) as e:
        _run_demo(demo_world, demo_s3.LocalRequest())
    assert e.value.exit_code == 1
    assert "jar" not in demo_world["events"]
    assert str(error) in capsys.readouterr().err


def test_own_bucket_demo_raises_the_stop_grace_to_90s(demo_world, monkeypatch):
    monkeypatch.delenv("FORCE_AFTER", raising=False)
    _run_demo(demo_world, _own())
    assert os.environ["FORCE_AFTER"] == "90"


def test_own_bucket_demo_keeps_a_grace_the_user_set_in_the_shell(demo_world, monkeypatch):
    monkeypatch.setenv("FORCE_AFTER", "7")
    _run_demo(demo_world, _own())
    assert os.environ["FORCE_AFTER"] == "7"


def test_own_bucket_demo_keeps_a_grace_the_user_set_in_qod_setup(demo_world, monkeypatch):
    from qod_cli.commands import demo as demo_cmd

    monkeypatch.delenv("FORCE_AFTER", raising=False)
    monkeypatch.setattr(demo_cmd, "load_start_env", lambda: {"FORCE_AFTER": "15"})
    _run_demo(demo_world, _own())
    assert "FORCE_AFTER" not in os.environ


@pytest.mark.parametrize("request_", [demo_s3.LocalRequest(), None])
def test_local_and_plain_demos_leave_the_grace_alone(demo_world, monkeypatch, request_):
    monkeypatch.delenv("FORCE_AFTER", raising=False)
    _run_demo(demo_world, request_)
    assert "FORCE_AFTER" not in os.environ
