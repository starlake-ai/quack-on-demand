"""The qod wheel bundles qod-node for `qod fleet join` (qod_cli/qod_node.py).

The bundle is a copy of the vendored binaries (native/qod-node/binaries), made by
scripts/release/refresh-qod-node-binaries.sh and nothing else: these tests are the gate that keeps it
fresh, then pin how a bundled binary is installed beside the pinned libduckdb and how its version is
read.
"""

import os
import subprocess
import sys
from pathlib import Path

import pytest

from qod_cli import qod_node

REPO = Path(__file__).resolve().parents[2]
VENDORED = REPO / "native" / "qod-node" / "binaries"
BUNDLED = REPO / "cli" / "src" / "qod_cli" / "qod-node"

MARKER = f"qod-node-build-info:protocol={qod_node.NODE_PROTOCOL};duckdb=v2.0.0;duckdb_source=d54cbbe3df"


def test_the_bundle_is_a_copy_of_the_vendored_binaries():
    vendored = sorted(p.relative_to(VENDORED) for p in VENDORED.glob("*/qod-node"))
    bundled = sorted(p.relative_to(BUNDLED) for p in BUNDLED.glob("*/qod-node"))
    assert vendored, "no vendored qod-node binary"
    assert bundled == vendored, "run scripts/release/refresh-qod-node-binaries.sh"
    for rel in vendored:
        assert (BUNDLED / rel).read_bytes() == (VENDORED / rel).read_bytes(), \
            f"{rel} differs: run scripts/release/refresh-qod-node-binaries.sh"
    assert (BUNDLED / "VERSION").read_text() == (VENDORED / "VERSION").read_text()


def test_the_bundled_binary_of_this_machine_is_found():
    plat = qod_node.platform_dir()
    if plat is None or not (BUNDLED / plat / "qod-node").is_file():
        pytest.skip(f"no qod-node is vendored for {plat or sys.platform}")
    assert qod_node.bundled(plat).read_bytes() == (BUNDLED / plat / "qod-node").read_bytes()


@pytest.mark.parametrize("os_name,arch,expected", [
    ("linux", "x86_64", "linux-x86_64"), ("linux", "aarch64", "linux-aarch64"),
    ("darwin", "arm64", "osx-aarch64"), ("darwin", "x86_64", "osx-x86_64"), ("win32", "AMD64", None),
])
def test_platform_dir(os_name, arch, expected):
    assert qod_node.platform_dir(os_name, arch) == expected


@pytest.mark.skipif(sys.platform == "win32", reason="symlinked lib directory")
def test_install_puts_the_binary_beside_a_lib_link_and_never_replaces_another_build(tmp_path, monkeypatch):
    fake = tmp_path / "bundle" / "qod-node"
    fake.parent.mkdir()
    fake.write_bytes(b"#!/bin/sh\necho build-1\n")
    monkeypatch.setattr(qod_node, "bundled", lambda plat: fake)
    lib = tmp_path / "duckdb" / "2.0.0" / "lib"
    lib.mkdir(parents=True)
    exe = qod_node.install(tmp_path / "cache", lib, os_name="linux", arch="x86_64")
    assert exe.name == "qod-node" and exe.parent.name == "bin"
    assert exe.parent.parent.parent == tmp_path / "cache" / "qod-node"
    assert os.access(exe, os.X_OK)
    assert os.readlink(exe.parent.parent / "lib") == str(lib)
    # The same build installs to the same place; another libduckdb re-points the link.
    lib2 = tmp_path / "duckdb2" / "lib"
    lib2.mkdir(parents=True)
    assert qod_node.install(tmp_path / "cache", lib2, os_name="linux", arch="x86_64") == exe
    assert os.readlink(exe.parent.parent / "lib") == str(lib2)
    # Another build gets a directory of its own: a running node's binary is never replaced.
    fake.write_bytes(b"#!/bin/sh\necho build-2\n")
    other = qod_node.install(tmp_path / "cache", lib, os_name="linux", arch="x86_64")
    assert other != exe and exe.read_bytes() == b"#!/bin/sh\necho build-1\n"


def test_install_names_the_option_when_nothing_is_bundled(tmp_path):
    with pytest.raises(RuntimeError, match="--qod-node-bin"):
        qod_node.install(tmp_path, tmp_path, os_name="win32", arch="AMD64")


class _Done:
    def __init__(self, out, rc=0):
        self.stdout, self.returncode = out, rc


def test_probe_version_reads_the_build_marker():
    assert qod_node.probe_version(Path("q"), run=lambda *a, **kw: _Done(MARKER + "\n")) == "2.0.0"


def test_probe_version_is_none_when_the_binary_does_not_start(tmp_path):
    # A shared build that cannot load its libduckdb, or loads another release, fails --build-info.
    assert qod_node.probe_version(Path("q"), run=lambda *a, **kw: _Done(MARKER, rc=1)) is None
    assert qod_node.probe_version(Path("q"), run=lambda *a, **kw: _Done("garbage")) is None

    def boom(*a, **kw):
        raise OSError("no such file")

    assert qod_node.probe_version(Path("q"), run=boom) is None

    def slow(*a, **kw):
        raise subprocess.TimeoutExpired("q", 10)

    assert qod_node.probe_version(Path("q"), run=slow) is None


def test_the_node_protocol_is_the_one_qod_node_compiles_in():
    cmake = (REPO / "native" / "qod-node" / "CMakeLists.txt").read_text()
    assert f"set(QOD_NODE_PROTOCOL {qod_node.NODE_PROTOCOL})" in cmake


def test_probe_version_refuses_a_qod_node_of_another_protocol():
    # A node one protocol behind enforces nothing in the engine; the manager's handles keep it
    # unroutable, and the join refuses to start it at all.
    for other in (qod_node.NODE_PROTOCOL - 1, qod_node.NODE_PROTOCOL + 1, 14):
        out = MARKER.replace(f"protocol={qod_node.NODE_PROTOCOL};", f"protocol={other};")
        with pytest.raises(qod_node.WrongProtocol, match=f"node protocol {other}"):
            qod_node.probe_version(Path("q"), run=lambda *a, **kw: _Done(out + "\n"))
