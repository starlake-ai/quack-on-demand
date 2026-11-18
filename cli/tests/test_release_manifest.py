import hashlib
import importlib.util
import json
import pathlib
import shutil
import sys

import pytest

from qod_cli import launcher, qod_node

REPO = pathlib.Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("release_manifest", REPO / "scripts/release/release_manifest.py")
release_manifest = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = release_manifest
_spec.loader.exec_module(release_manifest)

VERSION = "0.13.1"
PIN_FILES = (
    "cli/src/qod_cli/launcher.py",
    "cli/src/qod_cli/qod_node.py",
    "native/qod-node/CMakeLists.txt",
    "project/Versions.scala",
    "version.sbt",
    "src/main/resources/release/upgrades.json",
)


def _published(folder: pathlib.Path, name: str, body: bytes, companion: str | None = None) -> pathlib.Path:
    path = folder / name
    path.write_bytes(body)
    digest = hashlib.sha256(body).hexdigest() if companion is None else companion
    (folder / f"{name}.sha256").write_text(f"{digest}  {name}\n")
    return path


def _pypi(version: str = VERSION, packagetypes: tuple[str, ...] = ("bdist_wheel", "sdist")) -> dict:
    names = {"bdist_wheel": f"qod-{version}-py3-none-any.whl", "sdist": f"qod-{version}.tar.gz"}
    return {
        "info": {"name": "qod", "version": version},
        "urls": [
            {
                "filename": names[kind],
                "packagetype": kind,
                "url": f"https://files.pythonhosted.org/packages/xx/{names[kind]}",
                "size": 1234,
                "digests": {"sha256": hashlib.sha256(names[kind].encode()).hexdigest(), "md5": "x"},
            }
            for kind in packagetypes
        ],
    }


@pytest.fixture
def released(tmp_path):
    jar = _published(tmp_path, f"quack-on-demand-assembly-{VERSION}.jar", b"jar bytes")
    exes = {arch: _published(tmp_path, f"qod-node-windows-{arch}.exe", arch.encode()) for arch in ("x86_64", "aarch64")}
    return jar, exes


def _fake_repo(tmp_path: pathlib.Path) -> pathlib.Path:
    repo = tmp_path / "repo"
    for rel in PIN_FILES:
        (repo / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(REPO / rel, repo / rel)
    shutil.copytree(REPO / release_manifest.CHANGELOG_DIR, repo / release_manifest.CHANGELOG_DIR)
    return repo


def test_read_pins_states_what_the_cli_and_the_build_pin():
    pins = release_manifest.read_pins()
    assert pins.github_repo == launcher.GITHUB_REPO
    assert pins.java_min_major == launcher.MIN_JAVA_MAJOR
    assert pins.duckdb_version == launcher.DUCKDB_CLI_VERSION
    assert pins.duckdb_sha256 == launcher._DUCKDB_SHA256[launcher.DUCKDB_CLI_VERSION]
    assert pins.qod_node_protocol == qod_node.NODE_PROTOCOL


def test_build_manifest_lists_every_published_file(released):
    jar, exes = released
    manifest = release_manifest.build_manifest(
        release_manifest.read_pins(), VERSION, "abc123", jar, exes, _pypi()
    )
    assets = f"https://github.com/{launcher.GITHUB_REPO}/releases/download/v{VERSION}"
    assert manifest["schema"] == 1
    assert (manifest["version"], manifest["tag"], manifest["commit"]) == (VERSION, f"v{VERSION}", "abc123")
    assert manifest["java_min_major"] == launcher.MIN_JAVA_MAJOR
    assert manifest["duckdb_version"] == launcher.DUCKDB_CLI_VERSION
    assert manifest["qod_node_protocol"] == qod_node.NODE_PROTOCOL
    assert manifest["jar"] == {
        "version": VERSION,
        "name": jar.name,
        "url": f"{assets}/{jar.name}",
        "size": len(b"jar bytes"),
        "sha256": hashlib.sha256(b"jar bytes").hexdigest(),
    }
    assert [(e["arch"], e["name"], e["url"]) for e in manifest["qod_node_windows"]] == [
        ("aarch64", "qod-node-windows-aarch64.exe", f"{assets}/qod-node-windows-aarch64.exe"),
        ("x86_64", "qod-node-windows-x86_64.exe", f"{assets}/qod-node-windows-x86_64.exe"),
    ]
    assert manifest["qod_node_windows"][1]["sha256"] == hashlib.sha256(b"x86_64").hexdigest()
    wheel = manifest["wheel"]
    assert (wheel["package"], wheel["version"]) == ("qod", VERSION)
    assert [(f["name"], f["kind"]) for f in wheel["files"]] == [
        (f"qod-{VERSION}-py3-none-any.whl", "wheel"),
        (f"qod-{VERSION}.tar.gz", "sdist"),
    ]
    pinned = launcher._DUCKDB_SHA256[launcher.DUCKDB_CLI_VERSION]
    assert {a["name"]: a["sha256"] for a in manifest["duckdb_assets"]} == pinned
    assert all(
        a["url"] == f"https://github.com/duckdb/duckdb/releases/download/v{launcher.DUCKDB_CLI_VERSION}/{a['name']}"
        for a in manifest["duckdb_assets"]
    )


def test_build_manifest_refuses_a_companion_naming_another_sha256(tmp_path, released):
    _, exes = released
    jar = _published(tmp_path, f"quack-on-demand-assembly-{VERSION}.jar", b"jar bytes", companion="0" * 64)
    with pytest.raises(release_manifest.ManifestError, match="does not state the sha256"):
        release_manifest.build_manifest(release_manifest.read_pins(), VERSION, "c", jar, exes, _pypi())


def test_build_manifest_refuses_another_releases_jar(tmp_path, released):
    _, exes = released
    jar = _published(tmp_path, "quack-on-demand-assembly-0.13.0.jar", b"old")
    with pytest.raises(release_manifest.ManifestError, match="not quack-on-demand-assembly-0.13.1.jar"):
        release_manifest.build_manifest(release_manifest.read_pins(), VERSION, "c", jar, exes, _pypi())


@pytest.mark.parametrize(
    ("pypi", "message"),
    [
        (_pypi(version="0.13.0"), "is for qod 0.13.0, not qod 0.13.1"),
        (_pypi(packagetypes=("sdist",)), "no wheel for qod 0.13.1"),
    ],
)
def test_build_manifest_refuses_a_pypi_answer_without_this_releases_wheel(released, pypi, message):
    jar, exes = released
    with pytest.raises(release_manifest.ManifestError, match=message):
        release_manifest.build_manifest(release_manifest.read_pins(), VERSION, "c", jar, exes, pypi)


@pytest.mark.parametrize(
    ("rel", "old", "new", "message"),
    [
        ("project/Versions.scala", 'val duckdb         = "', 'val duckdb         = "9.', "the build links"),
        ("native/qod-node/CMakeLists.txt", "set(QOD_NODE_PROTOCOL ", "set(QOD_NODE_PROTOCOL 9", "node protocol"),
        ("cli/src/qod_cli/launcher.py", 'DUCKDB_CLI_VERSION = "', 'DUCKDB_CLI_VERSION = "9.', "provisions DuckDB 9."),
    ],
)
def test_read_pins_refuses_files_that_disagree(tmp_path, rel, old, new, message):
    repo = _fake_repo(tmp_path)
    text = (repo / rel).read_text(encoding="utf-8")
    assert old in text
    (repo / rel).write_text(text.replace(old, new, 1), encoding="utf-8")
    with pytest.raises(release_manifest.ManifestError, match=message):
        release_manifest.read_pins(repo)


def test_read_pins_refuses_a_duckdb_release_with_no_asset_sha256(tmp_path):
    repo = _fake_repo(tmp_path)
    for rel, old in (
        ("cli/src/qod_cli/launcher.py", f'DUCKDB_CLI_VERSION = "{launcher.DUCKDB_CLI_VERSION}"'),
        ("project/Versions.scala", f'val duckdb         = "{launcher.DUCKDB_CLI_VERSION}'),
    ):
        text = (repo / rel).read_text(encoding="utf-8")
        assert old in text
        (repo / rel).write_text(text.replace(old, old.replace(launcher.DUCKDB_CLI_VERSION, "9.9.9"), 1), encoding="utf-8")
    with pytest.raises(release_manifest.ManifestError, match="pins no DuckDB 9.9.9 asset sha256"):
        release_manifest.read_pins(repo)


def test_main_writes_release_json(tmp_path, released, capsys):
    jar, exes = released
    pypi = tmp_path / "pypi.json"
    pypi.write_text(json.dumps(_pypi()))
    out = tmp_path / "release.json"
    argv = ["--version", VERSION, "--commit", "c0ffee", "--jar", str(jar), "--pypi-json", str(pypi), "--out", str(out)]
    for arch, path in exes.items():
        argv += ["--qod-node-exe", f"{arch}={path}"]
    assert release_manifest.main(argv) == 0
    manifest = json.loads(out.read_text())
    assert manifest["commit"] == "c0ffee"
    assert len(manifest["qod_node_windows"]) == 2

    pypi.write_text(json.dumps(_pypi(version="0.0.1")))
    out.unlink()
    assert release_manifest.main(argv) == 1
    assert "release_manifest: the PyPI answer is for qod 0.0.1" in capsys.readouterr().err
    assert not out.exists()

    pypi.write_text(json.dumps({"info": ["qod"], "urls": []}))
    assert release_manifest.main(argv) == 1
    assert capsys.readouterr().err.startswith("release_manifest: AttributeError: ")
    assert not out.exists()


def test_main_check_pins_needs_no_release_files(tmp_path, capsys):
    assert release_manifest.main(["--check-pins"]) == 0
    assert f"pins agree: DuckDB {launcher.DUCKDB_CLI_VERSION}" in capsys.readouterr().out

    repo = _fake_repo(tmp_path)
    cmake = repo / "native/qod-node/CMakeLists.txt"
    cmake.write_text(cmake.read_text(encoding="utf-8").replace("set(QOD_NODE_PROTOCOL ", "set(QOD_NODE_PROTOCOL 9", 1))
    assert release_manifest.main(["--check-pins", "--repo", str(repo)]) == 1
    assert "node protocol" in capsys.readouterr().err


def test_main_refuses_a_manifest_without_its_release_files(capsys):
    with pytest.raises(SystemExit) as exit_info:
        release_manifest.main(["--version", VERSION])
    assert exit_info.value.code == 2
    assert "missing --commit, --jar, --pypi-json" in capsys.readouterr().err


def test_release_json_carries_the_upgrade_path_and_the_rollback_rule(tmp_path, released):
    jar, exes = released
    pypi = tmp_path / "pypi.json"
    pypi.write_text(json.dumps(_pypi()))
    out = tmp_path / "release.json"
    argv = ["--version", VERSION, "--commit", "c0ffee", "--jar", str(jar), "--pypi-json", str(pypi), "--out", str(out)]
    for arch, path in exes.items():
        argv += ["--qod-node-exe", f"{arch}={path}"]
    assert release_manifest.main(argv) == 0
    manifest = json.loads(out.read_text())
    entry = next(e for e in release_manifest.read_upgrades() if e["version"] == VERSION)
    assert manifest["min_from_version"] == entry["min_from_version"]
    assert manifest["rollback_without_restore"] is entry["rollback_without_restore"]
    assert manifest["rollback_blocked_by"] == entry["rollback_blocked_by"]
    assert manifest["upgrade_notes"] == entry["notes"]
    # Upgrading from min_from_version applies the steps of every release after it, in order.
    versions = [step["version"] for step in manifest["upgrade_steps"]]
    assert versions == sorted(versions, key=lambda v: tuple(int(p) for p in v.split(".")))
    assert entry["min_from_version"] not in versions
    assert "0.11.0" in versions
    assert all(step["notes"].startswith("https://") for step in manifest["upgrade_steps"])


def test_upgrade_fields_refuse_a_release_with_no_entry():
    with pytest.raises(release_manifest.ManifestError, match="no entry for 9.9.9"):
        release_manifest.upgrade_fields(release_manifest.read_upgrades(), "9.9.9")


def _upgrades(repo: pathlib.Path) -> tuple[pathlib.Path, dict]:
    path = repo / release_manifest.UPGRADES_FILE
    return path, json.loads(path.read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    ("change", "message"),
    [
        (lambda d: d["releases"].reverse(), "is not after the release listed before it"),
        (lambda d: d["releases"][0].update(rollback_blocked_by=[]), "rollback_blocked_by must name"),
        (lambda d: d["releases"][1].update(rollback_blocked_by=["a column"]), "rollback_blocked_by must name"),
        (lambda d: d["releases"][1].update(min_from_version="9.0.0"), "upgrades from a later release"),
        (lambda d: d["releases"][0]["steps"][0].update(when="sometime"), "each step needs when"),
        (lambda d: d["releases"][0].update(notes="CHANGELOG.md"), "needs a notes URL"),
        (lambda d: d["releases"][0].update(version="0.11"), "is not a release number"),
    ],
)
def test_read_upgrades_refuses_a_malformed_file(tmp_path, change, message):
    repo = _fake_repo(tmp_path)
    path, data = _upgrades(repo)
    change(data)
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(release_manifest.ManifestError, match=message):
        release_manifest.read_upgrades(repo)


def test_check_pins_refuses_a_tree_whose_release_has_no_entry(tmp_path, capsys):
    repo = _fake_repo(tmp_path)
    (repo / "version.sbt").write_text('ThisBuild / version := "9.9.9-SNAPSHOT"\n', encoding="utf-8")
    assert release_manifest.main(["--check-pins", "--repo", str(repo)]) == 1
    assert "has no entry for 9.9.9 (version.sbt)" in capsys.readouterr().err
    assert release_manifest.main(["--check-pins"]) == 0
    assert "upgrade paths:" in capsys.readouterr().out


def _changelog(repo: pathlib.Path, name: str, body: str) -> None:
    (repo / release_manifest.CHANGELOG_DIR / f"{name}.yaml").write_text(body, encoding="utf-8")


_ADD_COLUMN = "databaseChangeLog:\n  - changeSet:\n      id: x\n      changes:\n        - addColumn:\n"
_DROP_COLUMN = "databaseChangeLog:\n  - changeSet:\n      id: x\n      changes:\n        - dropColumn:\n"


def _list_in_last_release(repo: pathlib.Path, name: str, rollback: bool = True) -> None:
    path, data = _upgrades(repo)
    data["releases"][-1]["changesets"].append(name)
    if not rollback:
        data["releases"][-1].update(rollback_without_restore=False, rollback_blocked_by=["drops a column"])
    path.write_text(json.dumps(data), encoding="utf-8")


def test_check_pins_refuses_a_changelog_file_no_release_lists(tmp_path):
    repo = _fake_repo(tmp_path)
    _changelog(repo, "0999-new-thing", _ADD_COLUMN)
    with pytest.raises(release_manifest.ManifestError, match="no release lists 0999-new-thing"):
        release_manifest.check_upgrades(repo)
    _list_in_last_release(repo, "0999-new-thing")
    release_manifest.check_upgrades(repo)


def test_check_pins_refuses_a_release_listing_a_missing_or_shared_changelog_file(tmp_path):
    repo = _fake_repo(tmp_path)
    _list_in_last_release(repo, "0999-not-there")
    with pytest.raises(release_manifest.ManifestError, match="does not hold"):
        release_manifest.check_upgrades(repo)
    repo = _fake_repo(tmp_path / "again")
    _list_in_last_release(repo, "0055-drop-pool-lockdown")
    with pytest.raises(release_manifest.ManifestError, match="is listed by both 0.11.0"):
        release_manifest.check_upgrades(repo)


def test_check_pins_refuses_a_one_way_change_in_a_release_that_rolls_back(tmp_path):
    repo = _fake_repo(tmp_path)
    _changelog(repo, "0999-drop-it", _DROP_COLUMN)
    _list_in_last_release(repo, "0999-drop-it")
    with pytest.raises(release_manifest.ManifestError, match="holds 'dropColumn'"):
        release_manifest.check_upgrades(repo)
    repo = _fake_repo(tmp_path / "declared")
    _changelog(repo, "0999-drop-it", _DROP_COLUMN)
    _list_in_last_release(repo, "0999-drop-it", rollback=False)
    release_manifest.check_upgrades(repo)


def test_a_comment_naming_a_one_way_change_is_not_one(tmp_path):
    repo = _fake_repo(tmp_path)
    _changelog(repo, "0999-additive", _ADD_COLUMN + "        # unlike a dropColumn, this keeps the old shape\n")
    _list_in_last_release(repo, "0999-additive")
    release_manifest.check_upgrades(repo)


def test_add_release_appends_the_next_entry_once(tmp_path, capsys):
    repo = _fake_repo(tmp_path)
    last = release_manifest.read_upgrades(repo)[-1]
    major, minor, patch = (int(p) for p in last["version"].split("."))
    following = f"{major}.{minor}.{patch + 1}"
    assert release_manifest.main(["--add-release", following, "--repo", str(repo)]) == 0
    assert f"added {following}" in capsys.readouterr().out
    entries = release_manifest.read_upgrades(repo)
    assert entries[-1] == {
        "version": following,
        "changesets": [],
        "min_from_version": last["min_from_version"],
        "rollback_without_restore": True,
        "rollback_blocked_by": [],
        "notes": f"https://github.com/{launcher.GITHUB_REPO}/blob/v{following}/CHANGELOG.md",
        "steps": [],
    }
    assert entries[:-1] == release_manifest.read_upgrades()
    assert release_manifest.main(["--add-release", following, "--repo", str(repo)]) == 0
    assert f"already lists {following}" in capsys.readouterr().out
    assert len(release_manifest.read_upgrades(repo)) == len(entries)
    assert release_manifest.main(["--add-release", "0.0.1", "--repo", str(repo)]) == 1
    assert "is not after" in capsys.readouterr().err


def test_upgrade_notes_state_the_path_the_rollback_and_this_releases_steps(capsys):
    assert release_manifest.main(["--upgrade-notes", "0.11.0"]) == 0
    one_way = capsys.readouterr().out
    assert one_way.startswith("## Upgrading\n")
    assert "Upgrades directly from **0.10.0** or later" in one_way
    assert "**Does not roll back without a restore**" in one_way
    assert "0055 drops qodstate_pool.lockdown" in one_way
    assert "The manager refuses to boot until it is done." in one_way
    assert release_manifest.main(["--upgrade-notes", "0.12.2"]) == 0
    quiet = capsys.readouterr().out
    assert "Rolls back to the previous release without a restore" in quiet
    assert "Steps for this release" not in quiet
    assert release_manifest.main(["--upgrade-notes", "9.9.9"]) == 1
    assert "no entry for 9.9.9" in capsys.readouterr().err
