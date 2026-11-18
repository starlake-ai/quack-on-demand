#!/usr/bin/env python3
"""Write release.json, the machine-readable manifest of one quack-on-demand release.

Run by .github/workflows/release.yml (job release-manifest) once the jar, the Windows qod-node and
the qod wheel are published. It lists, in one file, what the release is made of and what each
piece needs: the jar, the qod files on PyPI and the Windows qod-node with their sha256, the Java
major the manager needs, the DuckDB release its nodes and native client link (with the sha256 of
each DuckDB asset the qod CLI downloads) and the node protocol. The pins are read from the tagged
tree, never restated: launcher.py and qod_node.py of the CLI, Versions.scala, qod-node's
CMakeLists.txt and the three Dockerfiles that download DuckDB (its release and the sha256 of each
asset they unpack); where two of them state one fact, they must agree or no manifest is written.

With --check-pins it only checks that the tree's pins agree: release-pins.yml runs it on every pull
request into main, and release.yml's build-jar before any channel publishes, so a disagreement stops
a release before it ships rather than after.

Standard library only, so it runs on a bare runner.

Usage: release_manifest.py --version X.Y.Z --commit SHA --jar FILE
           --qod-node-exe ARCH=FILE [...] --pypi-json FILE [--out release.json]
       release_manifest.py --check-pins [--repo DIR]
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

SCHEMA_VERSION = 1
REPO_ROOT = Path(__file__).resolve().parents[2]
DUCKDB_RELEASES = "https://github.com/duckdb/duckdb/releases/download"
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
# The images that download DuckDB release assets, and the assets each downloads (one per target
# architecture). Each pins their sha256 in a `duckdb_sha256` shell function (dockerfile_duckdb_pins).
DOCKERFILES = ("Dockerfile", "docker/quack-node/Dockerfile", "docker/fleet-worker/Dockerfile")
DOCKER_DUCKDB_ASSETS = tuple(
    f"duckdb-{kind}-linux-{arch}.tar.gz" for kind in ("cli", "shared-libs") for arch in ("amd64", "arm64")
)
_DOCKER_VERSION_RE = re.compile(r"^ARG DUCKDB_VERSION=(\S+)\s*$")
_DOCKER_PIN_RE = re.compile(r"^([^\s/()]+)/(\S+)\)\s*echo\s+([^\s;]+)\s*;;$")


class ManifestError(Exception):
    """The inputs or the tree do not make a consistent manifest."""


@dataclass(frozen=True)
class Pins:
    """What the tagged tree pins for a release."""

    github_repo: str
    java_min_major: int
    duckdb_version: str
    duckdb_sha256: dict[str, str]
    qod_node_protocol: int


def module_constants(path: Path, names: set[str]) -> dict[str, object]:
    """Read the literal values of module-level assignments without importing the module.

    Args:
        path: The Python source to read.
        names: The names to read; every one must be assigned a literal.

    Returns:
        The value of each name.

    Raises:
        ManifestError: When a name is not assigned a literal at module level.
    """
    found: dict[str, object] = {}
    for node in ast.parse(path.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            target, value = node.targets[0].id, node.value
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.value is not None:
            target, value = node.target.id, node.value
        else:
            continue
        if target in names:
            found[target] = ast.literal_eval(value)
    missing = sorted(names - found.keys())
    if missing:
        msg = f"{path}: no literal module-level {', '.join(missing)}"
        raise ManifestError(msg)
    return found


def _match_one(path: Path, pattern: str) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        msg = f"{path}: nothing matches {pattern!r}"
        raise ManifestError(msg)
    return match.group(1)


def dockerfile_duckdb_pins(path: Path, rel: str) -> tuple[str, dict[tuple[str, str], str]]:
    """Read the DuckDB release a Dockerfile builds and the asset sha256 it pins, from its text.

    The pins are the lines of its `duckdb_sha256` shell function, one `<version>/<asset>) echo
    <sha256> ;;` each, between `case "$1" in` and `esac`; any other line there is refused rather
    than skipped, so a pin written another way cannot escape the check.

    Args:
        path: The Dockerfile.
        rel: Its path in the repository, for the errors.

    Returns:
        The `ARG DUCKDB_VERSION` default, and the sha256 of each (version, asset).

    Raises:
        ManifestError: When the default, the function or a pin is missing, malformed or repeated.
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    defaults = [m.group(1) for m in (_DOCKER_VERSION_RE.match(line) for line in lines) if m]
    if len(defaults) != 1:
        msg = f"{rel}: expected one ARG DUCKDB_VERSION=<version>, found {len(defaults)}"
        raise ManifestError(msg)
    starts = [i for i, line in enumerate(lines) if line.strip() == "duckdb_sha256() {"]
    if len(starts) != 1:
        msg = f"{rel}: expected one duckdb_sha256() function pinning the DuckDB asset sha256, found {len(starts)}"
        raise ManifestError(msg)
    pins: dict[tuple[str, str], str] = {}
    for number, line in enumerate(lines[starts[0] + 1 :], start=starts[0] + 2):
        text = line.strip()
        if text == "}":
            break
        if not text or text.startswith("#") or text in ('case "$1" in', "esac"):
            continue
        match = _DOCKER_PIN_RE.match(text)
        if match is None:
            msg = f"{rel}:{number}: unreadable duckdb_sha256 line (expected '<version>/<asset>) echo <sha256> ;;')"
            raise ManifestError(msg)
        version, asset, digest = match.groups()
        if not _SHA256_RE.match(digest):
            msg = f"{rel}: malformed sha256 for {asset} of DuckDB {version}"
            raise ManifestError(msg)
        if (version, asset) in pins:
            msg = f"{rel}: pins {asset} of DuckDB {version} twice"
            raise ManifestError(msg)
        pins[(version, asset)] = digest
    else:
        msg = f"{rel}: the duckdb_sha256() function does not end"
        raise ManifestError(msg)
    return defaults[0], pins


def check_dockerfile_pins(repo: Path, duckdb_version: str, cli_pins: dict[str, dict[str, str]]) -> None:
    """Refuse a Dockerfile whose DuckDB release or asset sha256 disagree with the qod CLI's.

    Args:
        repo: The checkout.
        duckdb_version: The DuckDB release the qod CLI provisions.
        cli_pins: launcher._DUCKDB_SHA256, the asset sha256 per release.

    Raises:
        ManifestError: When a Dockerfile builds another release, pins no sha256 for an asset it
            downloads, or pins one the CLI does not.
    """
    for rel in DOCKERFILES:
        default, pins = dockerfile_duckdb_pins(repo / rel, rel)
        for asset in DOCKER_DUCKDB_ASSETS:
            if (default, asset) not in pins:
                msg = f"{rel} pins no sha256 for {asset} of DuckDB {default} (duckdb_sha256)"
                raise ManifestError(msg)
        for (version, asset), digest in sorted(pins.items()):
            expected = cli_pins.get(version, {}).get(asset)
            if expected is None:
                msg = f"{rel} pins a sha256 for {asset} of DuckDB {version}, launcher.py pins none (_DUCKDB_SHA256)"
                raise ManifestError(msg)
            if digest != expected:
                msg = f"{rel} pins sha256 {digest} for {asset} of DuckDB {version}, launcher.py pins {expected}"
                raise ManifestError(msg)
        if default != duckdb_version:
            msg = f"{rel} builds DuckDB {default} (ARG DUCKDB_VERSION), the qod CLI provisions {duckdb_version}"
            raise ManifestError(msg)


def read_pins(repo: Path = REPO_ROOT) -> Pins:
    """Read the release's pins from the tree, refusing two files that state one fact differently.

    Args:
        repo: The checkout of the release tag.

    Returns:
        The pins.

    Raises:
        ManifestError: When a pin is missing or two sources disagree.
    """
    launcher = module_constants(
        repo / "cli/src/qod_cli/launcher.py",
        {"GITHUB_REPO", "MIN_JAVA_MAJOR", "DUCKDB_CLI_VERSION", "_DUCKDB_SHA256"},
    )
    cli_protocol = module_constants(repo / "cli/src/qod_cli/qod_node.py", {"NODE_PROTOCOL"})["NODE_PROTOCOL"]
    node_protocol = int(_match_one(repo / "native/qod-node/CMakeLists.txt", r"^set\(QOD_NODE_PROTOCOL (\d+)\)"))
    sbt_duckdb = _match_one(repo / "project/Versions.scala", r'^\s*val duckdb\s*=\s*"(\d+\.\d+\.\d+)[^"]*"')

    duckdb_version = str(launcher["DUCKDB_CLI_VERSION"])
    if duckdb_version != sbt_duckdb:
        msg = f"the qod CLI provisions DuckDB {duckdb_version}, the build links {sbt_duckdb} (Versions.duckdb)"
        raise ManifestError(msg)
    if cli_protocol != node_protocol:
        msg = f"the qod CLI speaks node protocol {cli_protocol}, qod-node {node_protocol} (QOD_NODE_PROTOCOL)"
        raise ManifestError(msg)
    pinned = launcher["_DUCKDB_SHA256"]
    if not isinstance(pinned, dict) or duckdb_version not in pinned:
        msg = f"launcher.py pins no DuckDB {duckdb_version} asset sha256 (_DUCKDB_SHA256)"
        raise ManifestError(msg)
    digests = {str(name): str(digest) for name, digest in pinned[duckdb_version].items()}
    bad = sorted(name for name, digest in digests.items() if not _SHA256_RE.match(digest))
    if bad:
        msg = f"launcher.py pins a malformed sha256 for {', '.join(bad)}"
        raise ManifestError(msg)
    check_dockerfile_pins(
        repo,
        duckdb_version,
        {str(v): {str(a): str(d) for a, d in assets.items()} for v, assets in pinned.items()},
    )
    return Pins(
        github_repo=str(launcher["GITHUB_REPO"]),
        java_min_major=int(launcher["MIN_JAVA_MAJOR"]),
        duckdb_version=duckdb_version,
        duckdb_sha256=digests,
        qod_node_protocol=node_protocol,
    )


def sha256_of(path: Path) -> str:
    """Return the hex sha256 of a file."""
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def release_file(path: Path, url: str) -> dict[str, object]:
    """Describe a published file, checking it against its `.sha256` companion when one lies beside it.

    Args:
        path: The file as published.
        url: Where it is downloaded from.

    Returns:
        Its name, url, size and sha256.

    Raises:
        ManifestError: When the companion names another digest.
    """
    digest = sha256_of(path)
    companion = path.with_name(path.name + ".sha256")
    if companion.is_file():
        stated = companion.read_text(encoding="utf-8").split()
        if not stated or stated[0].lower() != digest:
            msg = f"{companion.name} does not state the sha256 of {path.name} ({digest})"
            raise ManifestError(msg)
    return {"name": path.name, "url": url, "size": path.stat().st_size, "sha256": digest}


def pypi_files(pypi: dict[str, object], package: str, version: str) -> list[dict[str, object]]:
    """The files PyPI serves for one release of a package, from its JSON API answer.

    Args:
        pypi: The answer of https://pypi.org/pypi/<package>/<version>/json.
        package: The package name the answer must be for.
        version: The release it must be for.

    Returns:
        Each file's name, kind, url, size and sha256.

    Raises:
        ManifestError: When the answer is for another release or holds no wheel.
    """
    info = pypi.get("info") or {}
    if info.get("name", "").lower() != package or info.get("version") != version:
        msg = f"the PyPI answer is for {info.get('name')} {info.get('version')}, not {package} {version}"
        raise ManifestError(msg)
    files = [
        {
            "name": f["filename"],
            "kind": {"bdist_wheel": "wheel"}.get(f["packagetype"], f["packagetype"]),
            "url": f["url"],
            "size": f["size"],
            "sha256": f["digests"]["sha256"],
        }
        for f in pypi.get("urls") or []
    ]
    if not any(f["kind"] == "wheel" for f in files):
        msg = f"PyPI lists no wheel for {package} {version}"
        raise ManifestError(msg)
    return sorted(files, key=lambda f: str(f["name"]))


def build_manifest(
    pins: Pins,
    version: str,
    commit: str,
    jar: Path,
    qod_node_exes: dict[str, Path],
    pypi: dict[str, object],
) -> dict[str, object]:
    """Assemble release.json.

    Args:
        pins: The tree's pins.
        version: The release version, without the leading v.
        commit: The commit the release tag names.
        jar: The published manager jar.
        qod_node_exes: The published Windows qod-node per architecture.
        pypi: PyPI's JSON answer for the qod release.

    Returns:
        The manifest.

    Raises:
        ManifestError: When the jar is not this release's.
    """
    expected_jar = f"quack-on-demand-assembly-{version}.jar"
    if jar.name != expected_jar:
        msg = f"the jar is {jar.name}, not {expected_jar}"
        raise ManifestError(msg)
    tag = f"v{version}"
    assets = f"https://github.com/{pins.github_repo}/releases/download/{tag}"
    return {
        "schema": SCHEMA_VERSION,
        "version": version,
        "tag": tag,
        "commit": commit,
        "java_min_major": pins.java_min_major,
        "duckdb_version": pins.duckdb_version,
        "qod_node_protocol": pins.qod_node_protocol,
        "jar": {"version": version, **release_file(jar, f"{assets}/{jar.name}")},
        "wheel": {"package": "qod", "version": version, "files": pypi_files(pypi, "qod", version)},
        "qod_node_windows": [
            {"arch": arch, **release_file(path, f"{assets}/{path.name}")}
            for arch, path in sorted(qod_node_exes.items())
        ],
        "duckdb_assets": [
            {"name": name, "url": f"{DUCKDB_RELEASES}/v{pins.duckdb_version}/{name}", "sha256": digest}
            for name, digest in sorted(pins.duckdb_sha256.items())
        ],
    }


def _arch_path(value: str) -> tuple[str, Path]:
    arch, sep, path = value.partition("=")
    if not sep or not arch or not path:
        msg = f"expected ARCH=FILE, got {value!r}"
        raise argparse.ArgumentTypeError(msg)
    return arch, Path(path)


def main(argv: list[str] | None = None) -> int:
    """Write release.json from the command line; see the module docstring."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check-pins", action="store_true", help="only check that the tree's pins agree")
    parser.add_argument("--version")
    parser.add_argument("--commit")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--qod-node-exe", action="append", default=[], type=_arch_path)
    parser.add_argument("--pypi-json", type=Path)
    parser.add_argument("--out", type=Path, default=Path("release.json"))
    parser.add_argument("--repo", type=Path, default=REPO_ROOT)
    args = parser.parse_args(argv)
    if args.check_pins:
        try:
            pins = read_pins(args.repo)
        except (ManifestError, OSError, ValueError, SyntaxError) as e:
            detail = str(e) if isinstance(e, ManifestError) else f"{type(e).__name__}: {e}"
            sys.stderr.write(f"release_manifest: {detail}\n")
            return 1
        sys.stdout.write(
            f"pins agree: DuckDB {pins.duckdb_version} ({len(pins.duckdb_sha256)} asset sha256, "
            f"{len(DOCKERFILES)} Dockerfiles), "
            f"node protocol {pins.qod_node_protocol}, Java {pins.java_min_major}\n"
        )
        return 0
    missing = [flag for flag in ("version", "commit", "jar", "pypi_json") if getattr(args, flag) is None]
    if missing:
        parser.error(f"missing {', '.join('--' + m.replace('_', '-') for m in missing)}")
    try:
        manifest = build_manifest(
            read_pins(args.repo),
            args.version,
            args.commit,
            args.jar,
            dict(args.qod_node_exe),
            json.loads(args.pypi_json.read_text(encoding="utf-8")),
        )
    except (ManifestError, OSError, KeyError, ValueError, TypeError, AttributeError, SyntaxError) as e:
        detail = str(e) if isinstance(e, ManifestError) else f"{type(e).__name__}: {e}"
        sys.stderr.write(f"release_manifest: {detail}\n")
        return 1
    args.out.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
