#!/usr/bin/env python3
"""Write release.json, the machine-readable manifest of one quack-on-demand release.

Run by .github/workflows/release.yml (job release-manifest) once the jar, the Windows qod-node and
the qod wheel are published. It lists, in one file, what the release is made of and what each
piece needs: the jar, the qod files on PyPI and the Windows qod-node with their sha256, the Java
major the manager needs, the DuckDB release its nodes and native client link (with the sha256 of
each DuckDB asset the qod CLI downloads) and the node protocol. The pins are read from the tagged
tree, never restated: launcher.py and qod_node.py of the CLI, Versions.scala and qod-node's
CMakeLists.txt; where two of them state one fact, they must agree or no manifest is written.

It also carries the release's upgrade path and rollback rule (#221, #222) from
src/main/resources/release/upgrades.json, the file the jar's boot gate reads: min_from_version,
rollback_without_restore, rollback_blocked_by, upgrade_notes and the upgrade_steps of every release
after min_from_version.

With --check-pins it only checks that the tree's pins agree, and that upgrades.json is well formed
and lists the release version.sbt builds: release-pins.yml runs it on every pull
request into main, and release.yml's build-jar before any channel publishes, so a disagreement stops
a release before it ships rather than after.

Standard library only, so it runs on a bare runner.

Usage: release_manifest.py --version X.Y.Z --commit SHA --jar FILE
           --qod-node-exe ARCH=FILE [...] --pypi-json FILE [--out release.json]
       release_manifest.py --check-pins [--repo DIR]
       release_manifest.py --add-release X.Y.Z [--repo DIR]
       release_manifest.py --upgrade-notes X.Y.Z [--repo DIR]
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


UPGRADES_FILE = "src/main/resources/release/upgrades.json"
_RELEASE_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
_STEP_WHEN = ("before", "during", "after")


def _release_number(value: object, where: str) -> tuple[int, int, int]:
    if not isinstance(value, str) or not _RELEASE_RE.match(value):
        msg = f"{UPGRADES_FILE}: {where} is not a release number X.Y.Z: {value!r}"
        raise ManifestError(msg)
    major, minor, patch = (int(p) for p in value.split("."))
    return major, minor, patch


def read_upgrades(repo: Path = REPO_ROOT) -> list[dict[str, object]]:
    """Read and check the upgrade paths and rollback rules of every release (#221, #222).

    The same file is bundled in the jar, where the boot's release gate (ReleaseGate) reads it.

    Args:
        repo: The checkout to read.

    Returns:
        The release entries, oldest first.

    Raises:
        ManifestError: When the file is malformed, out of order, or a release says it cannot be
            rolled back without saying why (or says why when it can).
    """
    data = json.loads((repo / UPGRADES_FILE).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("schema") != 1 or not isinstance(data.get("releases"), list):
        msg = f"{UPGRADES_FILE}: expected schema 1 and a releases list"
        raise ManifestError(msg)
    entries: list[dict[str, object]] = data["releases"]
    previous: tuple[int, int, int] | None = None
    for entry in entries:
        if not isinstance(entry, dict):
            msg = f"{UPGRADES_FILE}: a release entry is not an object"
            raise ManifestError(msg)
        name = entry.get("version")
        number = _release_number(name, "version")
        if previous is not None and number <= previous:
            msg = f"{UPGRADES_FILE}: {name} is not after the release listed before it"
            raise ManifestError(msg)
        previous = number
        if _release_number(entry.get("min_from_version"), f"{name} min_from_version") > number:
            msg = f"{UPGRADES_FILE}: {name} upgrades from a later release than itself"
            raise ManifestError(msg)
        rollback = entry.get("rollback_without_restore")
        blocked = entry.get("rollback_blocked_by")
        if not isinstance(rollback, bool) or not isinstance(blocked, list):
            msg = f"{UPGRADES_FILE}: {name} needs rollback_without_restore and rollback_blocked_by"
            raise ManifestError(msg)
        if rollback == bool(blocked) or not all(isinstance(b, str) and b.strip() for b in blocked):
            msg = (
                f"{UPGRADES_FILE}: {name}: rollback_blocked_by must name what makes the rollback "
                "one way exactly when rollback_without_restore is false"
            )
            raise ManifestError(msg)
        notes = entry.get("notes")
        if not isinstance(notes, str) or not notes.startswith("https://"):
            msg = f"{UPGRADES_FILE}: {name} needs a notes URL"
            raise ManifestError(msg)
        steps = entry.get("steps")
        if not isinstance(steps, list):
            msg = f"{UPGRADES_FILE}: {name} needs a steps list"
            raise ManifestError(msg)
        for step in steps:
            if (
                not isinstance(step, dict)
                or step.get("when") not in _STEP_WHEN
                or not isinstance(step.get("boot_refuses_until_done"), bool)
                or not isinstance(step.get("text"), str)
                or not step["text"].strip()
            ):
                msg = (
                    f"{UPGRADES_FILE}: {name}: each step needs when ({', '.join(_STEP_WHEN)}), "
                    "boot_refuses_until_done and text"
                )
                raise ManifestError(msg)
    return entries


CHANGELOG_DIR = "src/main/resources/db/changelog"
# Changelog files from this one on must each be listed by one release entry; the earlier ones
# predate upgrades.json, whose first entry (0.11.0) starts here.
FIRST_LISTED_CHANGESET = 47
# A change that a previous release, still reading the old shape, cannot run on.
_ONE_WAY_RE = re.compile(
    r"\b(dropColumn|dropTable|dropView|renameColumn|renameTable|renameView|modifyDataType|"
    r"addNotNullConstraint)\b|\bDROP\s+(TABLE|COLUMN|VIEW)\b|\bRENAME\s+(TO|COLUMN)\b|"
    r"\bALTER\s+COLUMN\s+\S+\s+(SET\s+DATA\s+)?TYPE\b|\bSET\s+NOT\s+NULL\b",
    re.IGNORECASE,
)


def check_changesets(repo: Path, entries: list[dict[str, object]]) -> None:
    """Refuse a changelog file no release lists (or two do), and a one-way one in a release that
    says it rolls back without a restore.

    Raises:
        ManifestError: Naming the file and the release.
    """
    files = {
        p.stem
        for p in (repo / CHANGELOG_DIR).glob("[0-9][0-9][0-9][0-9]-*.yaml")
        if int(p.stem[:4]) >= FIRST_LISTED_CHANGESET
    }
    owner: dict[str, str] = {}
    for entry in entries:
        listed = entry.get("changesets")
        if not isinstance(listed, list) or not all(isinstance(c, str) for c in listed):
            msg = f"{UPGRADES_FILE}: {entry['version']} needs a changesets list (changelog file names)"
            raise ManifestError(msg)
        for name in listed:
            if name in owner:
                msg = f"{UPGRADES_FILE}: {name} is listed by both {owner[name]} and {entry['version']}"
                raise ManifestError(msg)
            if name not in files:
                msg = f"{UPGRADES_FILE}: {entry['version']} lists {name}, which {CHANGELOG_DIR} does not hold"
                raise ManifestError(msg)
            owner[name] = str(entry["version"])
            if entry["rollback_without_restore"]:
                text = (repo / CHANGELOG_DIR / f"{name}.yaml").read_text(encoding="utf-8")
                code = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))
                found = _ONE_WAY_RE.search(code)
                if found:
                    msg = (
                        f"{UPGRADES_FILE}: {entry['version']} says rollback_without_restore true, but "
                        f"{name} holds {found.group(0)!r}, which the previous release may not run on: "
                        "set it false and say why in rollback_blocked_by"
                    )
                    raise ManifestError(msg)
    unlisted = sorted(files - owner.keys())
    if unlisted:
        msg = (
            f"{UPGRADES_FILE}: no release lists {', '.join(unlisted)}: add each to the changesets of "
            "the release that adds it"
        )
        raise ManifestError(msg)


def add_release(repo: Path, version: str) -> bool:
    """Append the entry of the next release (release.sh, at the move to its snapshot).

    It copies the previous entry's min_from_version, says the release rolls back without a
    restore, and lists no step and no changeset: the pull requests of the release fill it in.
    The file keeps its layout (the entry is inserted as text before the closing bracket).

    Returns:
        False when the entry was already there.

    Raises:
        ManifestError: When `version` is not after the last entry.
    """
    path = repo / UPGRADES_FILE
    entries = read_upgrades(repo)
    if any(e["version"] == version for e in entries):
        return False
    if entries and _release_number(version, "version") <= _release_number(entries[-1]["version"], "version"):
        msg = f"{UPGRADES_FILE}: {version} is not after {entries[-1]['version']}"
        raise ManifestError(msg)
    min_from = entries[-1]["min_from_version"] if entries else version
    github = module_constants(repo / "cli/src/qod_cli/launcher.py", {"GITHUB_REPO"})["GITHUB_REPO"]
    entry = (
        "    {\n"
        f'      "version": "{version}",\n'
        '      "changesets": [],\n'
        f'      "min_from_version": "{min_from}",\n'
        '      "rollback_without_restore": true,\n'
        '      "rollback_blocked_by": [],\n'
        f'      "notes": "https://github.com/{github}/blob/v{version}/CHANGELOG.md",\n'
        '      "steps": []\n'
        "    }\n"
    )
    text = path.read_text(encoding="utf-8")
    end = text.rstrip().rfind("]")
    head = text[:end].rstrip()
    if entries:
        head += ","
    path.write_text(f"{head}\n{entry}  ]\n}}\n", encoding="utf-8")
    read_upgrades(repo)
    return True


def build_version(repo: Path = REPO_ROOT) -> str:
    """The release the tree builds (version.sbt), without a -SNAPSHOT suffix."""
    return _match_one(repo / "version.sbt", r'version\s*:=\s*"(\d+\.\d+\.\d+)[^"]*"')


def check_upgrades(repo: Path = REPO_ROOT) -> list[dict[str, object]]:
    """read_upgrades, and refuse a tree whose own release has no entry.

    Raises:
        ManifestError: As read_upgrades, or when version.sbt's release is not listed.
    """
    entries = read_upgrades(repo)
    check_changesets(repo, entries)
    version = build_version(repo)
    if not any(e["version"] == version for e in entries):
        msg = f"{UPGRADES_FILE} has no entry for {version} (version.sbt): add it"
        raise ManifestError(msg)
    return entries


def upgrade_fields(entries: list[dict[str, object]], version: str) -> dict[str, object]:
    """The release.json fields of `version`'s upgrade path and rollback (#221, #222).

    `upgrade_steps` lists the manual steps of every release after `min_from_version` up to this
    one, each tagged with the release that brought it: upgrading from release A, apply the steps
    of every release after A, in order.

    Raises:
        ManifestError: When `version` has no entry.
    """
    entry = next((e for e in entries if e["version"] == version), None)
    if entry is None:
        msg = f"{UPGRADES_FILE} has no entry for {version}"
        raise ManifestError(msg)
    floor = _release_number(entry["min_from_version"], "min_from_version")
    top = _release_number(version, "version")
    steps = [
        {"version": e["version"], **step, "notes": e["notes"]}
        for e in entries
        if floor < _release_number(e["version"], "version") <= top
        for step in e["steps"]
    ]
    return {
        "min_from_version": entry["min_from_version"],
        "rollback_without_restore": entry["rollback_without_restore"],
        "rollback_blocked_by": entry["rollback_blocked_by"],
        "upgrade_notes": entry["notes"],
        "upgrade_steps": steps,
    }


def upgrade_notes(entries: list[dict[str, object]], version: str) -> str:
    """The Upgrading block release.yml puts ahead of a release's generated notes (Markdown).

    Raises:
        ManifestError: When `version` has no entry.
    """
    fields = upgrade_fields(entries, version)
    lines = [
        "## Upgrading",
        "",
        f"- Upgrades directly from **{fields['min_from_version']}** or later: apply the steps of every "
        f"release after the one you run, in order ([this release's notes]({fields['upgrade_notes']})).",
    ]
    if fields["rollback_without_restore"]:
        lines.append("- Rolls back to the previous release without a restore of the control-plane database.")
    else:
        lines.append(
            "- **Does not roll back without a restore**: back up the control-plane database first. "
            + " ".join(f"{b.rstrip('.')}." for b in fields["rollback_blocked_by"])
        )
    own = [s for s in fields["upgrade_steps"] if s["version"] == version]
    if own:
        lines += ["", "Steps for this release:", ""]
        for step in own:
            refuses = " The manager refuses to boot until it is done." if step["boot_refuses_until_done"] else ""
            lines.append(f"- {step['when'].capitalize()} the upgrade: {step['text']}{refuses}")
    lines += ["", "The same, readable by a program: `release.json`, fields `min_from_version` to `upgrade_steps`.", ""]
    return "\n".join(lines)


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
    parser.add_argument("--add-release", metavar="X.Y.Z", help="append the next release's entry to upgrades.json")
    parser.add_argument("--upgrade-notes", metavar="X.Y.Z", help="print the release's Upgrading notes (Markdown)")
    parser.add_argument("--version")
    parser.add_argument("--commit")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--qod-node-exe", action="append", default=[], type=_arch_path)
    parser.add_argument("--pypi-json", type=Path)
    parser.add_argument("--out", type=Path, default=Path("release.json"))
    parser.add_argument("--repo", type=Path, default=REPO_ROOT)
    args = parser.parse_args(argv)
    if args.upgrade_notes:
        try:
            sys.stdout.write(upgrade_notes(read_upgrades(args.repo), args.upgrade_notes))
        except (ManifestError, OSError, ValueError) as e:
            detail = str(e) if isinstance(e, ManifestError) else f"{type(e).__name__}: {e}"
            sys.stderr.write(f"release_manifest: {detail}\n")
            return 1
        return 0
    if args.add_release:
        try:
            added = add_release(args.repo, args.add_release)
        except (ManifestError, OSError, ValueError, SyntaxError) as e:
            detail = str(e) if isinstance(e, ManifestError) else f"{type(e).__name__}: {e}"
            sys.stderr.write(f"release_manifest: {detail}\n")
            return 1
        sys.stdout.write(f"{UPGRADES_FILE}: {'added' if added else 'already lists'} {args.add_release}\n")
        return 0
    if args.check_pins:
        try:
            pins = read_pins(args.repo)
            upgrades = check_upgrades(args.repo)
        except (ManifestError, OSError, ValueError, SyntaxError) as e:
            detail = str(e) if isinstance(e, ManifestError) else f"{type(e).__name__}: {e}"
            sys.stderr.write(f"release_manifest: {detail}\n")
            return 1
        sys.stdout.write(
            f"pins agree: DuckDB {pins.duckdb_version} ({len(pins.duckdb_sha256)} asset sha256), "
            f"node protocol {pins.qod_node_protocol}, Java {pins.java_min_major}\n"
        )
        sys.stdout.write(f"upgrade paths: {len(upgrades)} releases, {build_version(args.repo)} listed\n")
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
        manifest.update(upgrade_fields(read_upgrades(args.repo), args.version))
    except (ManifestError, OSError, KeyError, ValueError, TypeError, AttributeError, SyntaxError) as e:
        detail = str(e) if isinstance(e, ManifestError) else f"{type(e).__name__}: {e}"
        sys.stderr.write(f"release_manifest: {detail}\n")
        return 1
    args.out.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
