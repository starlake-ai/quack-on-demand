"""Resolve and launch the quack-on-demand manager jar (used by `qod start --demo`).

The jar is not bundled in the wheel: it is fetched from the GitHub release
matching the CLI version (they release in lockstep), verified against the
.sha256 companion asset, and cached version-addressed under the user cache dir.
"""

import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import httpx

GITHUB_REPO = "starlake-ai/quack-on-demand"

# Without this flag Arrow auto-picks the netty allocator, which crashes against
# the newer Netty bundled in the assembly (NoSuchFieldError: chunkSize).
ARROW_ALLOCATOR_FLAG = "-Darrow.allocation.manager.type=Unsafe"

# Matches the toolchain the jar is built and shipped with (Temurin 21 in the
# Docker image and the auto-provisioned JRE); older JVMs are refused rather
# than risking runtime surprises on a codepath we never test.
MIN_JAVA_MAJOR = 21

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_JAVA_VERSION_RE = re.compile(r'version "(\d+)(?:\.(\d+))?')


def jar_name(version: str) -> str:
    return f"quack-on-demand-assembly-{version}.jar"


def jar_url(version: str) -> str:
    return f"https://github.com/{GITHUB_REPO}/releases/download/v{version}/{jar_name(version)}"


# Releases before this one predate the jar's demo subcommand: they silently
# ignore the extra CLI arg and boot a normal manager against the configured
# (default: localhost) Postgres, which is never what an evaluator wants.
MIN_DEMO_VERSION = "0.3.8"


def _version_key(v: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", v))


def version_lt(a: str, b: str) -> bool:
    return _version_key(a) < _version_key(b)


# Matches only a fully-downloaded, version-addressed jar: `.jar.partial` files
# (an interrupted download) must never be offered as a runnable fallback.
_CACHED_JAR_RE = re.compile(r"^quack-on-demand-assembly-(\d+(?:\.\d+)*)\.jar$")


def jar_cache_dir(cache_dir: Path | None = None) -> Path:
    """Where manager jars are cached. `JAR_CACHE_DIR` (a run-jar.sh convention)
    overrides the platform cache dir."""
    if cache_dir is not None:
        return cache_dir
    env_cache = os.environ.get("JAR_CACHE_DIR")
    return Path(env_cache) if env_cache else default_cache_dir() / "jars"


def cached_jar_versions(cache_dir: Path | None = None) -> list[str]:
    """Manager versions already in the jar cache, oldest first."""
    d = jar_cache_dir(cache_dir)
    if not d.is_dir():
        return []
    found = [m.group(1) for p in d.iterdir() if (m := _CACHED_JAR_RE.match(p.name))]
    return sorted(found, key=_version_key)


def newest_cached_jar(cache_dir: Path | None = None) -> str | None:
    """Highest manager version already in the jar cache, or None when there is
    none. This is the offline fallback: with GitHub unreachable, booting the
    manager the user already ran beats refusing to start at all."""
    versions = cached_jar_versions(cache_dir)
    return versions[-1] if versions else None


def resolved_jar_version(cli_version: str) -> str | None:
    """Jar version pinned by this CLI build, or None for dev builds (no
    matching release exists; the caller falls back to the latest release)."""
    if "dev" in cli_version:
        return None
    return cli_version


def parse_sha256(text: str) -> str:
    """First field of a `shasum -a 256` output line (the release asset format)."""
    digest = text.split()[0].lower() if text.split() else ""
    if not _SHA256_RE.match(digest):
        raise ValueError(f"not a sha256 digest: {text!r}")
    return digest


def parse_java_major(version_line: str) -> int | None:
    """Major version from `java -version` output; `1.8` style maps to 8."""
    m = _JAVA_VERSION_RE.search(version_line)
    if not m:
        return None
    major = int(m.group(1))
    if major == 1 and m.group(2):
        return int(m.group(2))
    return major


def java_major(java_path: str) -> int | None:
    try:
        proc = subprocess.run(
            [java_path, "-version"], capture_output=True, text=True, timeout=30
        )
    except OSError:
        return None
    # java -version historically prints to stderr.
    return parse_java_major(proc.stderr or proc.stdout)


def _which_java() -> str | None:
    return shutil.which("java")


def find_java(min_major: int = MIN_JAVA_MAJOR) -> str | None:
    """A usable JVM (JAVA_HOME first, then PATH), or None if none is recent enough."""
    candidates = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java")
    from_path = _which_java()
    if from_path:
        candidates.append(Path(from_path))
    for candidate in candidates:
        if candidate.is_file():
            major = java_major(str(candidate))
            if major is not None and major >= min_major:
                return str(candidate)
    return None


def build_jar_command(
    java: str, jar: str, args: list[str], java_opts: str | None = None
) -> list[str]:
    import shlex

    opts = shlex.split(java_opts) if java_opts else []
    return [java, *opts, ARROW_ALLOCATOR_FLAG, "-jar", jar, *args]


class IntegrityError(RuntimeError):
    """Downloaded artifact does not match its published sha256."""


def default_cache_dir() -> Path:
    import platformdirs

    return Path(platformdirs.user_cache_dir("qod"))


def default_data_dir() -> Path:
    """Durable state anchor for `qod start` (DuckLake data, certs): unlike the
    cache dir, wiping this loses data."""
    import platformdirs

    return Path(platformdirs.user_data_dir("qod"))


def latest_release_version() -> str:
    resp = httpx.get(
        f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest",
        follow_redirects=True,
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["tag_name"].lstrip("v")


def _version_tuple(v: str) -> tuple[int | None, ...]:
    """Dot-split into ints; a non-numeric part (e.g. "dev0") fails safe to None
    rather than raising, so a comparison against it can be caught and treated as
    "no hint" instead of crashing."""
    parts: list[int | None] = []
    for p in v.split("."):
        try:
            parts.append(int(p))
        except ValueError:
            parts.append(None)
    return tuple(parts)


def newer_release_hint(current: str, timeout_s: float = 2.0) -> str | None:
    """One-line hint when PyPI's latest `qod` release is newer than `current`.

    Purely decorative - an unpinned `uvx qod` freezes on the first version it
    ever resolved, and this makes that trap self-announcing without turning a
    routine `qod serve`/`qod start` into a network dependency. Returns None on
    ANY failure (offline, timeout, malformed response) and whenever `current`
    is a dev build (".dev0", never released to PyPI) or already current, so the
    caller can echo-or-skip with no further error handling.
    """
    if current.endswith(".dev0"):
        return None
    try:
        resp = httpx.get("https://pypi.org/pypi/qod/json", timeout=timeout_s)
        resp.raise_for_status()
        latest = resp.json()["info"]["version"]
        if _version_tuple(latest) <= _version_tuple(current):
            return None
    except Exception:
        return None
    return (
        f"note: qod {latest} is available (you have {current}); "
        "uvx users: uvx qod@latest"
    )


def _download(url: str, dest: Path, label: str) -> str:
    """Stream `url` to `dest`, returning the sha256 of what was written."""
    digest = hashlib.sha256()
    with httpx.stream("GET", url, follow_redirects=True, timeout=60) as resp:
        resp.raise_for_status()
        total = int(resp.headers.get("content-length", 0)) or None
        if sys.stderr.isatty():
            from rich.progress import Progress

            with Progress() as progress, dest.open("wb") as out:
                task = progress.add_task(f"downloading {label}", total=total)
                for chunk in resp.iter_bytes():
                    out.write(chunk)
                    digest.update(chunk)
                    progress.advance(task, len(chunk))
        else:
            with dest.open("wb") as out:
                for chunk in resp.iter_bytes():
                    out.write(chunk)
                    digest.update(chunk)
    return digest.hexdigest()


def ensure_jar(version: str, cache_dir: Path | None = None) -> Path:
    """The manager jar for `version`, downloading and sha256-verifying it into
    the version-addressed cache on first use. `JAR_CACHE_DIR` (a run-jar.sh
    convention) overrides the cache location."""
    cache_dir = jar_cache_dir(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    jar = cache_dir / jar_name(version)
    if jar.is_file():
        return jar

    sha_resp = httpx.get(jar_url(version) + ".sha256", follow_redirects=True, timeout=30)
    sha_resp.raise_for_status()
    expected = parse_sha256(sha_resp.text)

    partial = jar.with_suffix(".jar.partial")
    try:
        actual = _download(jar_url(version), partial, jar.name)
        if actual != expected:
            raise IntegrityError(
                f"sha256 mismatch for {jar.name}: expected {expected}, got {actual}"
            )
        partial.rename(jar)
    finally:
        partial.unlink(missing_ok=True)
    return jar


# Temurin JRE auto-provisioning (used when no system JVM is found). Adoptium
# ships glibc Linux builds only, hence the musl refusal in the demo command.

JRE_MAJOR = 21

_ADOPTIUM_OS = {"darwin": "mac", "linux": "linux", "win32": "windows"}
_ADOPTIUM_ARCH = {"x86_64": "x64", "amd64": "x64", "arm64": "aarch64", "aarch64": "aarch64"}


def adoptium_assets_url(os_name: str, arch: str) -> str:
    adoptium_os = _ADOPTIUM_OS[os_name]
    adoptium_arch = _ADOPTIUM_ARCH[arch.lower()]
    return (
        f"https://api.adoptium.net/v3/assets/latest/{JRE_MAJOR}/hotspot"
        f"?architecture={adoptium_arch}&image_type=jre&os={adoptium_os}&vendor=eclipse"
    )


def is_musl(root: Path = Path("/")) -> bool:
    return sys.platform == "linux" and (root / "etc" / "alpine-release").is_file()


def _find_extracted_java(cache_dir: Path) -> str | None:
    # Temurin layouts: <top>/bin/java (linux, windows: java.exe) and
    # <top>/Contents/Home/bin/java (mac).
    for pattern in ("*/bin/java", "*/Contents/Home/bin/java", "*/bin/java.exe"):
        for candidate in sorted(cache_dir.glob(pattern)):
            if candidate.is_file():
                return str(candidate)
    return None


def ensure_jre(
    cache_dir: Path | None = None,
    os_name: str = sys.platform,
    arch: str | None = None,
) -> str:
    """Path to a cached Temurin JRE's java, downloading it from Adoptium on
    first use (checksum-verified from the API metadata)."""
    import platform
    import tarfile
    import zipfile

    cache_dir = cache_dir if cache_dir is not None else default_cache_dir() / "jre"
    cache_dir.mkdir(parents=True, exist_ok=True)
    existing = _find_extracted_java(cache_dir)
    if existing:
        return existing

    arch = arch if arch is not None else platform.machine()
    resp = httpx.get(adoptium_assets_url(os_name, arch), follow_redirects=True, timeout=30)
    resp.raise_for_status()
    # /v3/assets/latest/... returns objects with a singular `binary` holding
    # `package` directly (not `binaries[]`, which is the feature_releases shape).
    package = resp.json()[0]["binary"]["package"]

    suffix = ".zip" if package["link"].endswith(".zip") else ".tar.gz"
    archive = cache_dir / f"temurin-jre{suffix}"
    try:
        actual = _download(package["link"], archive, f"Temurin {JRE_MAJOR} JRE")
        if actual != package["checksum"]:
            raise IntegrityError(
                f"sha256 mismatch for Temurin JRE: expected {package['checksum']}, got {actual}"
            )
        if suffix == ".zip":
            with zipfile.ZipFile(archive) as z:
                z.extractall(cache_dir)
        else:
            with tarfile.open(archive) as tar:
                try:
                    tar.extractall(cache_dir, filter="data")
                except TypeError:  # filter= needs >= 3.10.12; floor is 3.10.0
                    tar.extractall(cache_dir)
    finally:
        archive.unlink(missing_ok=True)

    java = _find_extracted_java(cache_dir)
    if java is None:
        raise RuntimeError(f"no bin/java found after extracting the Temurin JRE in {cache_dir}")
    return java


# DuckDB CLI provisioning. The spawn script runs the `duckdb` CLI; the demo
# pins it to the libquackwire ABI version (Versions.scala first 3 segments,
# see docs/duckdb-pin-bump-checklist.md) instead of trusting a system install.
DUCKDB_CLI_VERSION = "2.0.0"

_DUCKDB_PLAT = {
    ("darwin", "arm64"): "osx-universal",
    ("darwin", "x86_64"): "osx-universal",
    ("linux", "x86_64"): "linux-amd64",
    ("linux", "amd64"): "linux-amd64",
    ("linux", "aarch64"): "linux-arm64",
    ("linux", "arm64"): "linux-arm64",
    ("win32", "amd64"): "windows-amd64",
    ("win32", "x86_64"): "windows-amd64",
    # Windows on ARM (platform.machine() == "ARM64", e.g. Parallels on
    # Apple Silicon); duckdb ships native windows-arm64 CLI + lib assets.
    ("win32", "arm64"): "windows-arm64",
    ("win32", "aarch64"): "windows-arm64",
}


def duckdb_version() -> str:
    """The duckdb release to provision: `DUCKDB_VERSION` (run-jar.sh
    convention) or the pinned default."""
    return os.environ.get("DUCKDB_VERSION") or DUCKDB_CLI_VERSION


def duckdb_cache_root(cache_dir: Path | None = None) -> Path:
    """Root of the duckdb cache: `DUCKDB_CACHE_DIR` (run-jar.sh's air-gap
    convention) or <cache>/duckdb. Both are laid out <root>/<version>/{bin,lib},
    the layout the manager's libduckdb preload reads ($DUCKDB_CACHE_DIR/<abi>/lib),
    so a version bump or a DUCKDB_VERSION override never reuses another
    release's binaries. Earlier qod releases kept an unversioned
    <cache>/duckdb/{bin,lib} (DuckDB 1.x); it is left in place and never read."""
    env_cache = os.environ.get("DUCKDB_CACHE_DIR", "").strip()
    if env_cache:
        return Path(env_cache)
    base = cache_dir if cache_dir is not None else default_cache_dir()
    return base / "duckdb"


def _duckdb_dir(kind: str, cache_dir: Path | None) -> Path:
    """bin/lib dir for the duckdb artifacts of the release being provisioned."""
    return duckdb_cache_root(cache_dir) / duckdb_version() / kind


def _extract_tar_gz(archive: Path, dest: Path) -> None:
    """Unpack a DuckDB 2.x release asset (a flat .tar.gz) into `dest`, refusing
    members that would land outside it."""
    import tarfile

    with tarfile.open(archive, "r:gz") as t:
        if hasattr(tarfile, "data_filter"):
            t.extractall(dest, filter="data")
        else:
            root = dest.resolve()
            for m in t.getmembers():
                target = (dest / m.name).resolve()
                if not (m.isfile() or m.isdir()) or not target.is_relative_to(root):
                    raise RuntimeError(f"unexpected member {m.name!r} in {archive.name}")
            t.extractall(dest)


def duckdb_cli_url(os_name: str, arch: str) -> str:
    plat = _DUCKDB_PLAT[(os_name, arch.lower())]
    return (
        f"https://github.com/duckdb/duckdb/releases/download/"
        f"v{duckdb_version()}/duckdb-cli-{plat}.tar.gz"
    )


def ensure_duckdb_cli(
    cache_dir: Path | None = None,
    os_name: str = sys.platform,
    arch: str | None = None,
) -> Path:
    """bin dir holding the pinned `duckdb` CLI under `cache_dir`, downloading
    it from the DuckDB GitHub release on first use."""
    import platform

    bin_dir = _duckdb_dir("bin", cache_dir)
    duckdb = bin_dir / ("duckdb.exe" if os_name == "win32" else "duckdb")
    if duckdb.is_file():
        return bin_dir

    arch = arch if arch is not None else platform.machine()
    bin_dir.mkdir(parents=True, exist_ok=True)
    archive = bin_dir / "duckdb-cli.tar.gz"
    try:
        _download(duckdb_cli_url(os_name, arch), archive, f"duckdb {duckdb_version()} CLI")
        _extract_tar_gz(archive, bin_dir)
    finally:
        archive.unlink(missing_ok=True)
    if not duckdb.is_file():
        raise RuntimeError(f"no duckdb binary found after extracting the CLI archive in {bin_dir}")
    duckdb.chmod(0o755)
    return bin_dir


def libduckdb_url(os_name: str, arch: str) -> str:
    plat = _DUCKDB_PLAT[(os_name, arch.lower())]
    return (
        f"https://github.com/duckdb/duckdb/releases/download/"
        f"v{duckdb_version()}/duckdb-shared-libs-{plat}.tar.gz"
    )


def ensure_libduckdb(
    cache_dir: Path | None = None,
    os_name: str = sys.platform,
    arch: str | None = None,
) -> Path:
    """lib dir holding the pinned libduckdb shared library (required by the
    JNI native client: libquackwire links against this exact ABI)."""
    import platform

    lib_dir = _duckdb_dir("lib", cache_dir)
    lib_names = {"darwin": "libduckdb.dylib", "linux": "libduckdb.so", "win32": "duckdb.dll"}
    lib = lib_dir / lib_names[os_name]
    if lib.is_file():
        return lib_dir

    arch = arch if arch is not None else platform.machine()
    lib_dir.mkdir(parents=True, exist_ok=True)
    archive = lib_dir / "duckdb-shared-libs.tar.gz"
    try:
        _download(libduckdb_url(os_name, arch), archive, f"libduckdb {duckdb_version()}")
        _extract_tar_gz(archive, lib_dir)
    finally:
        archive.unlink(missing_ok=True)
    if not lib.is_file():
        raise RuntimeError(f"no {lib.name} found after extracting the libduckdb archive in {lib_dir}")
    return lib_dir


_WINDOWS_ARCH = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}


def node_release(jar: Path) -> str:
    """The GitHub release whose qod-node.exe goes with `jar`: v<version> for a release jar (named
    by jar_name), else the rolling `snapshot` pre-release (a snapshot or a local build)."""
    m = _CACHED_JAR_RE.match(Path(jar).name)
    return f"v{m.group(1)}" if m else "snapshot"


def qod_node_exe_url(release: str, arch: str) -> str:
    return f"https://github.com/{GITHUB_REPO}/releases/download/{release}/qod-node-windows-{arch}.exe"


def ensure_windows_qod_node(jar: Path, cache_dir: Path | None = None, arch: str | None = None) -> Path:
    """qod-node.exe, the process every node of a Windows manager runs: the static Windows build is
    a release asset (too large to bundle in the jar), so this downloads the one of `jar`'s release
    (node_release), sha256-checked, into <cache>/qod-node/<release>/qod-node.exe. A release's is
    reused once cached; the snapshot's is checked against its published digest on every call. The
    manager checks at boot that the binary speaks its node protocol and links its DuckDB, and
    refuses to boot otherwise."""
    import platform

    release = node_release(jar)
    machine = (arch or platform.machine()).lower()
    if machine not in _WINDOWS_ARCH:
        raise RuntimeError(f"no qod-node.exe is published for Windows on {machine}")
    base = cache_dir if cache_dir is not None else default_cache_dir()
    exe = base / "qod-node" / release / "qod-node.exe"
    if release != "snapshot" and exe.is_file():
        return exe
    url = qod_node_exe_url(release, _WINDOWS_ARCH[machine])
    sha_resp = httpx.get(url + ".sha256", follow_redirects=True, timeout=30)
    sha_resp.raise_for_status()
    expected = parse_sha256(sha_resp.text)
    if exe.is_file() and hashlib.sha256(exe.read_bytes()).hexdigest() == expected:
        return exe
    exe.parent.mkdir(parents=True, exist_ok=True)
    partial = exe.with_suffix(".exe.partial")
    try:
        actual = _download(url, partial, f"qod-node {release}")
        if actual != expected:
            raise IntegrityError(f"sha256 mismatch for {url}: expected {expected}, got {actual}")
        os.replace(partial, exe)
    finally:
        partial.unlink(missing_ok=True)
    return exe


def node_binary(jar: Path, cache_dir: Path | None = None, os_name: str = sys.platform, environ=None) -> Path | None:
    """The QOD_NODE_BIN to hand a launched manager: None off Windows (its jar bundles qod-node for
    Linux and macOS) or when QOD_NODE_BIN is already set (a local build); on Windows the qod-node.exe
    of the jar's release (ensure_windows_qod_node). Raises with what to do when it cannot."""
    environ = os.environ if environ is None else environ
    if os_name != "win32" or environ.get("QOD_NODE_BIN", "").strip():
        return None
    try:
        return ensure_windows_qod_node(jar, cache_dir)
    except Exception as e:
        raise RuntimeError(
            f"could not provision qod-node.exe for {Path(jar).name} ({e}); set QOD_NODE_BIN to a "
            "qod-node.exe of this release"
        ) from e


def bundled_spawn_script(name: str):
    """The spawn script shipped inside this package, as an importlib.resources
    Traversable (a copy of scripts/<name>; a repo test pins the two in sync)."""
    from importlib import resources

    return resources.files("qod_cli") / "scripts" / name


# Demo/benchmark loader scripts bundled for `qod start` LOAD_* parity with
# run-jar.sh. _load-common.sh is sourced by the three loaders.
LOADER_SCRIPTS = (
    "_load-common.sh",
    "load-tpch-dbgen.sh",
    "load-tpcds-dbgen.sh",
    "load-ssb-dbgen.sh",
)


def _materialize_scripts(dest_dir: Path, names: tuple[str, ...]) -> dict[str, Path]:
    dest_dir.mkdir(parents=True, exist_ok=True)
    out = {}
    for name in names:
        dest = dest_dir / name
        dest.write_bytes(bundled_spawn_script(name).read_bytes())
        dest.chmod(0o755)
        out[name] = dest
    return out


def materialize_spawn_scripts(dest_dir: Path) -> tuple[Path, Path]:
    """Copy the bundled node spawn scripts to `dest_dir` (executable), so the
    jar can invoke them from any working directory."""
    out = _materialize_scripts(dest_dir, ("spawn-quack-node.sh", "spawn-quack-node.ps1"))
    return out["spawn-quack-node.sh"], out["spawn-quack-node.ps1"]


def materialize_loader_scripts(dest_dir: Path) -> dict[str, Path]:
    """Copy the bundled demo loader scripts to `dest_dir` (executable)."""
    return _materialize_scripts(dest_dir, LOADER_SCRIPTS)


def runtime_env(
    base_env: dict,
    duckdb_bin: Path,
    spawn_sh: Path,
    spawn_ps1: Path,
    libduckdb_lib: Path,
    os_name: str = sys.platform,
    qod_node_bin: Path | None = None,
) -> dict:
    """Environment for a launched manager JVM: spawn scripts resolved
    absolutely, the pinned duckdb CLI first on PATH (the demo seeder and loaders
    inherit it), DUCKDB_BIN as an absolute pointer to that same exe, the native
    client's libduckdb: DUCKDB_CACHE_DIR for the manager's preload, plus the
    platform dynamic-loader path, and QOD_NODE_BIN when the caller provisioned
    the qod-node its nodes run (node_binary: Windows; the jar bundles it for
    Linux and macOS).

    `libduckdb_lib` is required: every launcher path boots a manager whose only
    client to its nodes is the native one, which cannot load without it."""
    env = dict(base_env)
    env["QOD_SPAWN_SCRIPT"] = str(spawn_sh)
    env["QOD_SPAWN_SCRIPT_WINDOWS"] = str(spawn_ps1)
    if qod_node_bin is not None:
        env["QOD_NODE_BIN"] = str(qod_node_bin)
    # duckdb.dll lives in the lib dir, so on Windows that dir goes on PATH too (the DLL search
    # order has no other variable); elsewhere the loader variable below carries it.
    path_dirs = [duckdb_bin, libduckdb_lib] if os_name == "win32" else [duckdb_bin]
    env["PATH"] = os.pathsep.join([*map(str, path_dirs), env.get("PATH", "")])
    # Pin the provisioned duckdb by absolute path, not just on PATH: the loaders prefer DUCKDB_BIN
    # over a PATH lookup, and on Windows the prepended PATH dir does not always reach a process
    # the JVM starts.
    exe = "duckdb.exe" if os_name == "win32" else "duckdb"
    env["DUCKDB_BIN"] = str(duckdb_bin / exe)
    # DuckDB 2.0's CLI switches to agent mode (markdown tables, JSON errors) when an agent env var
    # (AI_AGENT, CLAUDECODE) is set and stdout is not a terminal: pin the normal output for every
    # loader and seeder the manager starts, even from inside an agent session.
    env["DUCKDB_AGENT_MODE"] = "0"
    # The manager preloads $DUCKDB_CACHE_DIR/<abi>/lib/<libduckdb> by absolute path before
    # libquackwire, which is what makes the native load on every launcher path: macOS strips
    # DYLD_* from a SIP-protected JVM such as /usr/bin/java, and the rpath baked into the native
    # names its build machine. libduckdb_lib is <root>/<version>/lib (_duckdb_dir), so the root
    # is two levels up; <version> is the libquackwire ABI unless DUCKDB_VERSION overrides it.
    env["DUCKDB_CACHE_DIR"] = str(libduckdb_lib.parent.parent)
    var = {"darwin": "DYLD_LIBRARY_PATH", "linux": "LD_LIBRARY_PATH"}.get(os_name)
    if var:
        prev = env.get(var, "")
        env[var] = str(libduckdb_lib) + (os.pathsep + prev if prev else "")
    return env