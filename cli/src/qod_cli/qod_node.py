"""qod-node, the process every quack node runs (native/qod-node in the quack-on-demand repo).

`qod fleet join` runs its node through the bundled spawn script, which starts qod-node. This package
bundles qod-node for Linux and macOS (x86_64, aarch64) beside that script: the binaries the manager
jar carries (scripts/release/refresh-qod-node-binaries.sh copies them here, and
tests/test_qod_node_bundle.py fails until it has). Each links libduckdb dynamically and finds it
through its relative rpath, ../lib, so it is installed as <cache>/qod-node/<sha12>/bin/qod-node
beside a `lib` link to the pinned libduckdb the CLI provisions; <sha12> names the content, so a
running node's binary is never replaced.
"""

import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

_PLATFORMS = {
    ("linux", "x86_64"): "linux-x86_64",
    ("linux", "amd64"): "linux-x86_64",
    ("linux", "aarch64"): "linux-aarch64",
    ("linux", "arm64"): "linux-aarch64",
    ("darwin", "x86_64"): "osx-x86_64",
    ("darwin", "arm64"): "osx-aarch64",
    ("darwin", "aarch64"): "osx-aarch64",
}

_MARKER = re.compile(r"qod-node-build-info:protocol=(\d+);duckdb=v([0-9.]+);duckdb_source=([0-9a-f]+)")

#: The node protocol this qod speaks, the manager's of the same release (QOD_NODE_PROTOCOL in
#: native/qod-node/CMakeLists.txt; tests/test_qod_node_bundle.py pins the two).
NODE_PROTOCOL = 4


class WrongProtocol(RuntimeError):
    """A qod-node of another node protocol: the manager's handles do not reach it, and one of an
    earlier protocol enforces nothing in the engine, so the join refuses to run it."""


def platform_dir(os_name: str = sys.platform, arch: str | None = None) -> str | None:
    """The bundle's platform directory for this machine (`linux-x86_64`...), None for one with no
    bundled qod-node (Windows: its static qod-node.exe is a release asset)."""
    import platform

    return _PLATFORMS.get((os_name, (arch or platform.machine()).lower()))


def bundled(platform: str):
    """The bundled binary for `platform`, as an importlib.resources Traversable; None when this
    package carries none."""
    from importlib import resources

    f = resources.files("qod_cli") / "qod-node" / platform / "qod-node"
    return f if f.is_file() else None


def install(cache_dir: Path, lib_dir: Path, os_name: str = sys.platform, arch: str | None = None) -> Path:
    """Installs the bundled qod-node of this machine under `cache_dir` beside a `lib` link to
    `lib_dir` (the pinned libduckdb), and returns the executable."""
    plat = platform_dir(os_name, arch)
    src = bundled(plat) if plat else None
    if src is None:
        raise RuntimeError(
            f"this qod bundles no qod-node for {plat or os_name}: it has them for Linux and macOS "
            "(x86_64, aarch64). Pass --qod-node-bin with a qod-node built for this machine."
        )
    data = src.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    home = Path(cache_dir) / "qod-node" / digest[:12]
    exe = home / "bin" / "qod-node"
    exe.parent.mkdir(parents=True, exist_ok=True)
    if not (exe.is_file() and hashlib.sha256(exe.read_bytes()).hexdigest() == digest):
        # Written aside and renamed into place: a node may be running the binary already there.
        tmp = exe.with_name(f".qod-node-{os.getpid()}.tmp")
        tmp.write_bytes(data)
        os.replace(tmp, exe)
    exe.chmod(0o755)
    link = home / "lib"
    if not (link.is_symlink() and os.readlink(link) == str(lib_dir)):
        tmp_link = home / f".lib-{os.getpid()}"
        tmp_link.unlink(missing_ok=True)
        tmp_link.symlink_to(lib_dir)
        os.replace(tmp_link, link)
    return exe


def probe_version(binary: Path, run=subprocess.run) -> str | None:
    """The DuckDB release a qod-node is built against, from its `--build-info` ("2.0.0"); None when
    it does not start here (it checks the libduckdb it loads) or prints no marker. Raises
    WrongProtocol when it speaks another node protocol than NODE_PROTOCOL, as the manager's
    QodNodeBinary refuses one."""
    try:
        out = run([str(binary), "--build-info"], capture_output=True, text=True, errors="replace", timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    m = _MARKER.search(out.stdout or "")
    if out.returncode != 0 or not m:
        return None
    if int(m.group(1)) != NODE_PROTOCOL:
        raise WrongProtocol(
            f"{binary} speaks node protocol {m.group(1)}; this qod speaks {NODE_PROTOCOL}. Use the qod-node "
            "of this release: the one this qod bundles, or pass --qod-node-bin with a build of it."
        )
    return m.group(2)
