"""Resolve and launch the quack-on-demand manager jar (used by `qod demo`).

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


def version_lt(a: str, b: str) -> bool:
    def key(v: str) -> tuple[int, ...]:
        return tuple(int(part) for part in re.findall(r"\d+", v))

    return key(a) < key(b)


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
    if cache_dir is None:
        env_cache = os.environ.get("JAR_CACHE_DIR")
        cache_dir = Path(env_cache) if env_cache else default_cache_dir() / "jars"
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
    package = resp.json()[0]["binaries"][0]["package"]

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
DUCKDB_CLI_VERSION = "1.5.4"

_DUCKDB_PLAT = {
    ("darwin", "arm64"): "osx-universal",
    ("darwin", "x86_64"): "osx-universal",
    ("linux", "x86_64"): "linux-amd64",
    ("linux", "amd64"): "linux-amd64",
    ("linux", "aarch64"): "linux-arm64",
    ("linux", "arm64"): "linux-arm64",
    ("win32", "amd64"): "windows-amd64",
    ("win32", "x86_64"): "windows-amd64",
}


def duckdb_version() -> str:
    """The duckdb release to provision: `DUCKDB_VERSION` (run-jar.sh
    convention) or the pinned default."""
    return os.environ.get("DUCKDB_VERSION") or DUCKDB_CLI_VERSION


def _duckdb_dir(kind: str, cache_dir: Path | None) -> Path:
    """bin/lib dir for the duckdb artifacts. `DUCKDB_CACHE_DIR` follows
    run-jar.sh's air-gap layout ($DUCKDB_CACHE_DIR/$VERSION/{bin,lib});
    otherwise <cache>/duckdb/{bin,lib}."""
    env_cache = os.environ.get("DUCKDB_CACHE_DIR")
    if env_cache:
        return Path(env_cache) / duckdb_version() / kind
    base = cache_dir if cache_dir is not None else default_cache_dir()
    return base / "duckdb" / kind


def duckdb_cli_url(os_name: str, arch: str) -> str:
    plat = _DUCKDB_PLAT[(os_name, arch.lower())]
    return (
        f"https://github.com/duckdb/duckdb/releases/download/"
        f"v{duckdb_version()}/duckdb_cli-{plat}.zip"
    )


def ensure_duckdb_cli(
    cache_dir: Path | None = None,
    os_name: str = sys.platform,
    arch: str | None = None,
) -> Path:
    """bin dir holding the pinned `duckdb` CLI under `cache_dir`, downloading
    it from the DuckDB GitHub release on first use."""
    import platform
    import zipfile

    bin_dir = _duckdb_dir("bin", cache_dir)
    duckdb = bin_dir / ("duckdb.exe" if os_name == "win32" else "duckdb")
    if duckdb.is_file():
        return bin_dir

    arch = arch if arch is not None else platform.machine()
    bin_dir.mkdir(parents=True, exist_ok=True)
    archive = bin_dir / "duckdb_cli.zip"
    try:
        _download(duckdb_cli_url(os_name, arch), archive, f"duckdb {duckdb_version()} CLI")
        with zipfile.ZipFile(archive) as z:
            z.extractall(bin_dir)
    finally:
        archive.unlink(missing_ok=True)
    if not duckdb.is_file():
        raise RuntimeError(f"no duckdb binary found after extracting the CLI zip in {bin_dir}")
    duckdb.chmod(0o755)
    return bin_dir


def libduckdb_url(os_name: str, arch: str) -> str:
    plat = _DUCKDB_PLAT[(os_name, arch.lower())]
    return (
        f"https://github.com/duckdb/duckdb/releases/download/"
        f"v{duckdb_version()}/libduckdb-{plat}.zip"
    )


def ensure_libduckdb(
    cache_dir: Path | None = None,
    os_name: str = sys.platform,
    arch: str | None = None,
) -> Path:
    """lib dir holding the pinned libduckdb shared library (required by the
    JNI native client: libquackwire links against this exact ABI)."""
    import platform
    import zipfile

    lib_dir = _duckdb_dir("lib", cache_dir)
    lib_names = {"darwin": "libduckdb.dylib", "linux": "libduckdb.so", "win32": "duckdb.dll"}
    lib = lib_dir / lib_names[os_name]
    if lib.is_file():
        return lib_dir

    arch = arch if arch is not None else platform.machine()
    lib_dir.mkdir(parents=True, exist_ok=True)
    archive = lib_dir / "libduckdb.zip"
    try:
        _download(libduckdb_url(os_name, arch), archive, f"libduckdb {duckdb_version()}")
        with zipfile.ZipFile(archive) as z:
            z.extractall(lib_dir)
    finally:
        archive.unlink(missing_ok=True)
    if not lib.is_file():
        raise RuntimeError(f"no {lib.name} found after extracting the libduckdb zip in {lib_dir}")
    return lib_dir


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
    app_home: Path,
    duckdb_bin: Path,
    spawn_sh: Path,
    spawn_ps1: Path,
    libduckdb_lib: Path | None = None,
    os_name: str = sys.platform,
) -> dict:
    """Environment for a launched manager JVM: spawn scripts resolved
    absolutely, the pinned duckdb CLI first on PATH (spawned nodes and the demo
    seeder inherit it), QOD_APP_HOME so LocalQuackBackend prefixes node PATHs
    the same way, and (when the native client's libduckdb is provisioned) the
    platform dynamic-loader path."""
    env = dict(base_env)
    env["QOD_SPAWN_SCRIPT"] = str(spawn_sh)
    env["QOD_SPAWN_SCRIPT_WINDOWS"] = str(spawn_ps1)
    env["QOD_APP_HOME"] = str(app_home)
    env["PATH"] = str(duckdb_bin) + os.pathsep + env.get("PATH", "")
    if libduckdb_lib is not None:
        # On Windows the DLL resolves through PATH, already prefixed above.
        var = {"darwin": "DYLD_LIBRARY_PATH", "linux": "LD_LIBRARY_PATH"}.get(os_name)
        if var:
            prev = env.get(var, "")
            env[var] = str(libduckdb_lib) + (os.pathsep + prev if prev else "")
    return env