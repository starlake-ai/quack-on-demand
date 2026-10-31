import os
import pathlib
import hashlib
import re
import sys

import httpx
import respx
import pytest

from qod_cli import launcher
from tests import _duckdb_cache

JAR_037 = "quack-on-demand-assembly-0.3.7.jar"
URL_037 = f"https://github.com/starlake-ai/quack-on-demand/releases/download/v0.3.7/{JAR_037}"


def test_jar_name():
    assert launcher.jar_name("0.3.7") == JAR_037


def test_jar_url():
    assert launcher.jar_url("0.3.7") == URL_037


def test_resolved_jar_version_release_passthrough():
    assert launcher.resolved_jar_version("0.3.7") == "0.3.7"


def test_resolved_jar_version_dev_is_none():
    assert launcher.resolved_jar_version("0.3.8.dev0") is None


def test_parse_sha256_standard_shasum_line():
    sha = "f537b3a166890c06c86d88cbfe374ea4406e5eeeae389dfd23e98852e972b36f"
    assert launcher.parse_sha256(f"{sha}  {JAR_037}\n") == sha


def test_parse_sha256_rejects_garbage():
    with pytest.raises(ValueError):
        launcher.parse_sha256("")
    with pytest.raises(ValueError):
        launcher.parse_sha256("nothexdigest  file.jar")

def test_parse_java_major_modern():
    assert launcher.parse_java_major('openjdk version "21.0.3" 2024-04-16') == 21


def test_parse_java_major_legacy_1_8():
    assert launcher.parse_java_major('java version "1.8.0_291"') == 8


def test_parse_java_major_garbage_is_none():
    assert launcher.parse_java_major("no version here") is None


def test_find_java_prefers_java_home(tmp_path, monkeypatch):
    java = tmp_path / "bin" / "java"
    java.parent.mkdir(parents=True)
    java.write_text("")
    java.chmod(0o755)
    monkeypatch.setenv("JAVA_HOME", str(tmp_path))
    monkeypatch.setattr(launcher, "java_major", lambda p: 21)
    assert launcher.find_java() == str(java)


def test_find_java_rejects_too_old(tmp_path, monkeypatch):
    java = tmp_path / "bin" / "java"
    java.parent.mkdir(parents=True)
    java.write_text("")
    java.chmod(0o755)
    monkeypatch.setenv("JAVA_HOME", str(tmp_path))
    monkeypatch.setattr(launcher, "java_major", lambda p: 11)
    monkeypatch.setattr(launcher, "_which_java", lambda: None)
    assert launcher.find_java() is None


def test_find_java_falls_back_to_path(tmp_path, monkeypatch):
    # A real file, not a hardcoded POSIX literal: find_java() re-checks
    # candidate.is_file() against the real filesystem, and "/usr/bin/java"
    # only happens to exist on the ubuntu-latest GH runner image (not on
    # Windows), so pin an actual on-disk stand-in instead.
    java = tmp_path / "java"
    java.write_text("")
    java.chmod(0o755)
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.setattr(launcher, "_which_java", lambda: str(java))
    monkeypatch.setattr(launcher, "java_major", lambda p: 21)
    assert launcher.find_java() == str(java)


def test_find_java_floor_is_21(tmp_path, monkeypatch):
    java = tmp_path / "bin" / "java"
    java.parent.mkdir(parents=True)
    java.write_text("")
    java.chmod(0o755)
    monkeypatch.setenv("JAVA_HOME", str(tmp_path))
    monkeypatch.setattr(launcher, "java_major", lambda p: 17)
    monkeypatch.setattr(launcher, "_which_java", lambda: None)
    assert launcher.MIN_JAVA_MAJOR == 21
    assert launcher.find_java() is None


def test_build_jar_command_includes_arrow_flag():
    cmd = launcher.build_jar_command("/usr/bin/java", "/cache/qod.jar", ["demo", "--flag"])
    assert cmd == [
        "/usr/bin/java",
        "-Darrow.allocation.manager.type=Unsafe",
        "-jar",
        "/cache/qod.jar",
        "demo",
        "--flag",
    ]


def test_ensure_jar_cache_hit_no_network(tmp_path):
    (tmp_path / JAR_037).write_bytes(b"cached")
    with respx.mock:  # no mocked routes: any network call would error
        assert launcher.ensure_jar("0.3.7", cache_dir=tmp_path) == tmp_path / JAR_037


@respx.mock
def test_ensure_jar_downloads_and_verifies(tmp_path):
    payload = b"jarbytes"
    sha = hashlib.sha256(payload).hexdigest()
    respx.get(URL_037).mock(return_value=httpx.Response(200, content=payload))
    respx.get(URL_037 + ".sha256").mock(
        return_value=httpx.Response(200, text=f"{sha}  {JAR_037}\n")
    )
    path = launcher.ensure_jar("0.3.7", cache_dir=tmp_path)
    assert path == tmp_path / JAR_037
    assert path.read_bytes() == payload


@respx.mock
def test_ensure_jar_sha_mismatch_refuses_and_deletes(tmp_path):
    respx.get(URL_037).mock(return_value=httpx.Response(200, content=b"tampered"))
    respx.get(URL_037 + ".sha256").mock(
        return_value=httpx.Response(200, text="0" * 64 + f"  {JAR_037}\n")
    )
    with pytest.raises(launcher.IntegrityError):
        launcher.ensure_jar("0.3.7", cache_dir=tmp_path)
    assert list(tmp_path.iterdir()) == []


@respx.mock
def test_latest_release_version():
    respx.get("https://api.github.com/repos/starlake-ai/quack-on-demand/releases/latest").mock(
        return_value=httpx.Response(200, json={"tag_name": "v0.3.7"})
    )
    assert launcher.latest_release_version() == "0.3.7"


def test_adoptium_assets_url_platform_mapping():
    assert launcher.adoptium_assets_url("darwin", "arm64") == (
        "https://api.adoptium.net/v3/assets/latest/21/hotspot"
        "?architecture=aarch64&image_type=jre&os=mac&vendor=eclipse"
    )
    assert launcher.adoptium_assets_url("linux", "x86_64") == (
        "https://api.adoptium.net/v3/assets/latest/21/hotspot"
        "?architecture=x64&image_type=jre&os=linux&vendor=eclipse"
    )
    assert launcher.adoptium_assets_url("win32", "AMD64") == (
        "https://api.adoptium.net/v3/assets/latest/21/hotspot"
        "?architecture=x64&image_type=jre&os=windows&vendor=eclipse"
    )


def test_is_musl_false_outside_linux(monkeypatch):
    monkeypatch.setattr(launcher.sys, "platform", "darwin")
    assert launcher.is_musl() is False


def test_is_musl_true_on_alpine(tmp_path, monkeypatch):
    monkeypatch.setattr(launcher.sys, "platform", "linux")
    (tmp_path / "etc").mkdir()
    (tmp_path / "etc" / "alpine-release").write_text("3.20.0\n")
    assert launcher.is_musl(root=tmp_path) is True


def _jre_tarball(tmp_path):
    import io
    import tarfile

    java_bin = tmp_path / "stage" / "jdk-21.0.3+9-jre" / "bin" / "java"
    java_bin.parent.mkdir(parents=True)
    java_bin.write_text("#!/bin/sh\n")
    java_bin.chmod(0o755)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        tar.add(tmp_path / "stage" / "jdk-21.0.3+9-jre", arcname="jdk-21.0.3+9-jre")
    return buf.getvalue()


@respx.mock
def test_ensure_jre_downloads_verifies_and_extracts(tmp_path, monkeypatch):
    monkeypatch.setattr(launcher.sys, "platform", "linux")
    tarball = _jre_tarball(tmp_path)
    pkg_url = "https://example.com/temurin-jre.tar.gz"
    respx.get(launcher.adoptium_assets_url("linux", "x86_64")).mock(
        return_value=httpx.Response(
            200,
            json=[
                {
                    "binary": {
                        "package": {
                            "link": pkg_url,
                            "checksum": hashlib.sha256(tarball).hexdigest(),
                        }
                    }
                }
            ],
        )
    )
    respx.get(pkg_url).mock(return_value=httpx.Response(200, content=tarball))
    java = launcher.ensure_jre(cache_dir=tmp_path / "jre", os_name="linux", arch="x86_64")
    # _find_extracted_java returns str(Path); on Windows that renders with
    # backslashes even though os_name="linux" only steers the download URL,
    # so compare path components rather than a POSIX "bin/java" literal.
    java_path = pathlib.Path(java)
    assert java_path.name == "java" and java_path.parent.name == "bin"
    assert (tmp_path / "jre").as_posix() in java_path.as_posix()


@respx.mock
def test_ensure_jre_checksum_mismatch_refuses(tmp_path, monkeypatch):
    monkeypatch.setattr(launcher.sys, "platform", "linux")
    tarball = _jre_tarball(tmp_path)
    pkg_url = "https://example.com/temurin-jre.tar.gz"
    respx.get(launcher.adoptium_assets_url("linux", "x86_64")).mock(
        return_value=httpx.Response(
            200,
            json=[{"binary": {"package": {"link": pkg_url, "checksum": "0" * 64}}}],
        )
    )
    respx.get(pkg_url).mock(return_value=httpx.Response(200, content=tarball))
    with pytest.raises(launcher.IntegrityError):
        launcher.ensure_jre(cache_dir=tmp_path / "jre", os_name="linux", arch="x86_64")


def test_ensure_jre_reuses_extracted(tmp_path):
    java = tmp_path / "jre" / "jdk-21.0.3+9-jre" / "bin" / "java"
    java.parent.mkdir(parents=True)
    java.write_text("")
    java.chmod(0o755)
    with respx.mock:  # no routes: reuse must not hit the network
        got = launcher.ensure_jre(cache_dir=tmp_path / "jre", os_name="linux", arch="x86_64")
    assert got == str(java)


ARROW_FLAG = "-Darrow.allocation.manager.type=Unsafe"


def _capture_exec(monkeypatch):
    from qod_cli.commands import demo as demo_cmd

    captured = {}
    monkeypatch.setattr(
        launcher, "ensure_duckdb_cli", lambda cache_dir=None, **kw: pathlib.Path("/stub/db/bin")
    )
    monkeypatch.setattr(
        launcher, "ensure_libduckdb", lambda cache_dir=None, **kw: pathlib.Path("/stub/db/lib")
    )
    monkeypatch.setattr(launcher, "node_binary", lambda *a, **kw: None)
    monkeypatch.setattr(
        launcher,
        "materialize_spawn_scripts",
        lambda dest: (pathlib.Path("/stub/spawn.sh"), pathlib.Path("/stub/spawn.ps1")),
    )

    def fake_exec(cmd, env=None):
        captured["cmd"], captured["env"] = cmd, env

    monkeypatch.setattr(demo_cmd, "_exec", fake_exec)
    return captured


def test_demo_launches_local_jar_with_system_java(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    captured = _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo", "--jar", str(jar)])
    assert result.exit_code == 0, result.output
    assert captured["cmd"] == ["/usr/bin/java", ARROW_FLAG, "-jar", str(jar), "demo"]


def test_demo_passes_extra_args_to_the_jar(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    captured = _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo", "--jar", str(jar), "--sf", "1"])
    assert result.exit_code == 0, result.output
    assert captured["cmd"][-3:] == ["demo", "--sf", "1"]


def test_demo_dev_build_falls_back_to_latest_release(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "quack-on-demand-assembly-0.3.8.jar"
    jar.write_text("")
    asked = {}

    def fake_ensure_jar(version, cache_dir=None):
        asked["version"] = version
        return jar

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "latest_release_version", lambda: "0.3.8")
    monkeypatch.setattr(launcher, "ensure_jar", fake_ensure_jar)
    captured = _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo"])  # CLI __version__ is a .dev0 build
    assert result.exit_code == 0, result.output
    assert asked["version"] == "0.3.8"
    assert captured["cmd"][3] == str(jar)


def test_demo_version_option_pins_the_jar(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "quack-on-demand-assembly-0.9.9.jar"
    jar.write_text("")
    asked = {}

    def fake_ensure_jar(version, cache_dir=None):
        asked["version"] = version
        return jar

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "ensure_jar", fake_ensure_jar)
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo", "--version", "0.9.9"])
    assert result.exit_code == 0, result.output
    assert asked["version"] == "0.9.9"


def test_demo_without_java_provisions_jre(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    monkeypatch.setattr(launcher, "find_java", lambda: None)
    monkeypatch.setattr(launcher, "is_musl", lambda root=None: False)
    monkeypatch.setattr(launcher, "ensure_jre", lambda cache_dir=None: "/cache/jre/bin/java")
    captured = _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo", "--jar", str(jar)])
    assert result.exit_code == 0, result.output
    assert captured["cmd"][0] == "/cache/jre/bin/java"


def test_resolve_java_downloads_without_prompting_even_on_a_tty(monkeypatch):
    import typer

    from qod_cli.commands import _launch

    def no_prompt(*a, **kw):
        raise AssertionError("typer.confirm must not be called")

    monkeypatch.delenv("JAVA_BIN", raising=False)
    monkeypatch.setattr(launcher, "find_java", lambda: None)
    monkeypatch.setattr(launcher, "is_musl", lambda root=None: False)
    monkeypatch.setattr(launcher, "ensure_jre", lambda cache_dir=None: "/cache/jre/bin/java")
    monkeypatch.setattr(typer, "confirm", no_prompt)
    monkeypatch.setattr(_launch.sys.stdin, "isatty", lambda: True)
    assert _launch.resolve_java() == "/cache/jre/bin/java"


def test_demo_without_java_on_musl_fails_with_apk_hint(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    monkeypatch.setattr(launcher, "find_java", lambda: None)
    monkeypatch.setattr(launcher, "is_musl", lambda root=None: True)
    result = runner.invoke(app, ["start", "--demo", "--jar", str(jar)])
    assert result.exit_code == 1
    assert "apk add" in result.output


def test_demo_integrity_error_refuses_with_message(runner, monkeypatch):
    from qod_cli.main import app

    def bad_jar(version, cache_dir=None):
        raise launcher.IntegrityError("sha256 mismatch for x")

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "ensure_jar", bad_jar)
    result = runner.invoke(app, ["start", "--demo", "--version", "0.9.9"])
    assert result.exit_code == 1
    assert "refusing to run" in result.output


def test_demo_download_failure_suggests_local_jar(runner, monkeypatch):
    from qod_cli.main import app

    def down(version, cache_dir=None):
        raise httpx.ConnectError("boom")

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "ensure_jar", down)
    result = runner.invoke(app, ["start", "--demo", "--version", "0.9.9"])
    assert result.exit_code == 1
    assert "--jar" in result.output


def test_version_lt():
    assert launcher.version_lt("0.3.7", "0.3.8") is True
    assert launcher.version_lt("0.3.8", "0.3.8") is False
    assert launcher.version_lt("0.10.0", "0.9.9") is False


def test_demo_refuses_jar_versions_predating_the_demo(runner, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo", "--version", "0.3.7"])
    assert result.exit_code == 1
    assert "0.3.8" in result.output


def test_demo_refuses_when_latest_release_predates_the_demo(runner, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "latest_release_version", lambda: "0.3.7")
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo"])
    assert result.exit_code == 1
    assert "0.3.8" in result.output


def test_duckdb_cli_url_platform_mapping(monkeypatch):
    monkeypatch.delenv("DUCKDB_VERSION", raising=False)
    base = f"https://github.com/duckdb/duckdb/releases/download/v{launcher.DUCKDB_CLI_VERSION}"
    assert launcher.duckdb_cli_url("darwin", "arm64") == f"{base}/duckdb-cli-osx-universal.tar.gz"
    assert launcher.duckdb_cli_url("linux", "x86_64") == f"{base}/duckdb-cli-linux-amd64.tar.gz"
    assert launcher.duckdb_cli_url("linux", "aarch64") == f"{base}/duckdb-cli-linux-arm64.tar.gz"
    assert launcher.duckdb_cli_url("win32", "AMD64") == f"{base}/duckdb-cli-windows-amd64.tar.gz"
    # Windows on ARM (e.g. Parallels on Apple Silicon): platform.machine() is
    # "ARM64"; duckdb ships native windows-arm64 assets.
    assert launcher.duckdb_cli_url("win32", "ARM64") == f"{base}/duckdb-cli-windows-arm64.tar.gz"


def _tar_gz(name, content):
    """A DuckDB 2.x release asset: a flat .tar.gz holding one file."""
    import io
    import tarfile

    data = content.encode()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as t:
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o755
        t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def _duckdb_cli_archive():
    return _tar_gz("duckdb", "#!/bin/sh\n")


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="NTFS has no POSIX exec bit; duckdb.chmod(0o755) is a no-op on Windows",
)
@respx.mock
def test_ensure_duckdb_cli_downloads_and_extracts(tmp_path):
    respx.get(launcher.duckdb_cli_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_duckdb_cli_archive())
    )
    bin_dir = launcher.ensure_duckdb_cli(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    duckdb = pathlib.Path(bin_dir) / "duckdb"
    assert duckdb.is_file()
    assert duckdb.stat().st_mode & 0o111


def test_ensure_duckdb_cli_reuses_cached(tmp_path):
    bin_dir, _ = _duckdb_cache.plant(tmp_path, os_name="linux")
    with respx.mock:  # no routes: reuse must not hit the network
        got = launcher.ensure_duckdb_cli(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert pathlib.Path(got) == bin_dir == tmp_path / "duckdb" / launcher.DUCKDB_CLI_VERSION / "bin"


def _plant_legacy_and_other_version(tmp_path):
    # The pre-2.0 unversioned layout and another release's versioned dir, both "provisioned".
    for home in (tmp_path / "duckdb", tmp_path / "duckdb" / "1.5.6"):
        for f in (home / "bin" / "duckdb", home / "lib" / "libduckdb.so"):
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text("1.5.6")


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="NTFS has no POSIX exec bit; duckdb.chmod(0o755) is a no-op on Windows",
)
@respx.mock
def test_ensure_duckdb_cli_does_not_reuse_another_versions_cache(tmp_path):
    # An upgraded CLI must not keep the duckdb a previous release provisioned: nodes would run
    # the old engine (Quack v1) against a manager speaking v3.
    _plant_legacy_and_other_version(tmp_path)
    route = respx.get(launcher.duckdb_cli_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_duckdb_cli_archive())
    )
    bin_dir = launcher.ensure_duckdb_cli(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert route.called
    assert pathlib.Path(bin_dir) == tmp_path / "duckdb" / launcher.DUCKDB_CLI_VERSION / "bin"
    assert (pathlib.Path(bin_dir) / "duckdb").read_text() != "1.5.6"


@respx.mock
def test_ensure_duckdb_cli_honours_a_duckdb_version_override_over_the_cached_pin(
    tmp_path, monkeypatch
):
    _duckdb_cache.plant(tmp_path, os_name="linux")
    monkeypatch.setenv("DUCKDB_VERSION", "2.0.1")
    route = respx.get(launcher.duckdb_cli_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_duckdb_cli_archive())
    )
    bin_dir = launcher.ensure_duckdb_cli(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert route.called and "v2.0.1/" in str(route.calls.last.request.url)
    assert pathlib.Path(bin_dir) == tmp_path / "duckdb" / "2.0.1" / "bin"


def test_bundled_spawn_scripts_match_the_canonical_ones():
    repo_scripts = pathlib.Path(__file__).resolve().parents[2] / "scripts"
    for name in ("spawn-quack-node.sh", "spawn-quack-node.ps1"):
        bundled = launcher.bundled_spawn_script(name)
        assert bundled.read_bytes() == (repo_scripts / name).read_bytes(), (
            f"{name} drifted: re-copy scripts/{name} into cli/src/qod_cli/scripts/"
        )


# A duckdb CLI launch on a code line: `duckdb <` / `duckdb -x` / `"$DUCKDB" <` / `.../duckdb" -x` in
# bash, `Invoke-DuckDb` / `& $duck...` / `FileName = $duckdb...` in PowerShell (not the
# `Get-Process duckdb -ErrorAction ...` teardown lookup).
_DUCKDB_CALL = re.compile(
    r'(?<!Process )\bduckdb"?\s+(<|-)|"\$DUCKDB"\s|Invoke-DuckDb\b|&\s*\$duck'
    r"|FileName\s*=\s*\$duck"
)
_AGENT_MODE_OFF = re.compile(r"DUCKDB_AGENT_MODE\s*=\s*'?0'?|-no-agent\b")


def test_scripts_running_the_duckdb_cli_pin_agent_mode_off():
    # DuckDB 2.0's CLI switches to agent mode (markdown tables, JSON errors) when an agent env var
    # (AI_AGENT, CLAUDECODE) is set and stdout is not a terminal, e.g. a manager started from
    # inside Claude Code. Every script that runs the CLI must pin DUCKDB_AGENT_MODE=0 (or -no-agent).
    repo = pathlib.Path(__file__).resolve().parents[2]
    candidates = [
        *(repo / "scripts").rglob("*.sh"),
        *(repo / "scripts").rglob("*.ps1"),
        *(repo / "cli" / "src" / "qod_cli" / "scripts").glob("*"),
    ]
    flagged = set()
    for path in sorted(p for p in candidates if p.is_file()):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        code = "\n".join(line for line in lines if not line.lstrip().startswith("#"))
        if _DUCKDB_CALL.search(code):
            rel = path.relative_to(repo).as_posix()
            flagged.add(rel)
            assert _AGENT_MODE_OFF.search(code), f"{rel} runs the duckdb CLI without DUCKDB_AGENT_MODE=0"
    # The detector must keep seeing the known launchers, or the loop above proves nothing. The spawn
    # scripts run qod-node, not the CLI.
    known = {
        f"{d}/{name}"
        for d in ("scripts", "cli/src/qod_cli/scripts")
        for name in (
            "_load-common.sh",
            "load-tpch-dbgen.sh",
            "load-tpcds-dbgen.sh",
            "load-ssb-dbgen.sh",
        )
    } | {
        "scripts/_load-common.ps1",
        "scripts/load-tpch-dbgen.ps1",
        "scripts/run-jar.sh",
        "scripts/run-jar.ps1",
    }
    assert known <= flagged, sorted(known - flagged)


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="NTFS has no POSIX exec bit; materialize's chmod(0o755) is a no-op on Windows",
)
def test_materialize_spawn_scripts_are_executable(tmp_path):
    sh, ps1 = launcher.materialize_spawn_scripts(tmp_path)
    assert sh.name == "spawn-quack-node.sh" and sh.stat().st_mode & 0o111
    assert ps1.name == "spawn-quack-node.ps1"


def test_demo_env_wires_spawn_script_and_duckdb(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin"},
        duckdb_bin=tmp_path / "duckdb" / "bin",
        spawn_sh=tmp_path / "scripts" / "spawn-quack-node.sh",
        spawn_ps1=tmp_path / "scripts" / "spawn-quack-node.ps1",
        libduckdb_lib=tmp_path / "duckdb" / "lib",
    )
    assert env["QOD_SPAWN_SCRIPT"] == str(tmp_path / "scripts" / "spawn-quack-node.sh")
    assert env["QOD_SPAWN_SCRIPT_WINDOWS"] == str(tmp_path / "scripts" / "spawn-quack-node.ps1")
    # Nothing reads QOD_APP_HOME any more, and no QOD_NODE_BIN unless the caller provisioned one.
    assert "QOD_APP_HOME" not in env and "QOD_NODE_BIN" not in env
    assert env["PATH"].startswith(str(tmp_path / "duckdb" / "bin"))
    assert env["PATH"].endswith("/usr/bin")
    # DUCKDB_BIN pins the provisioned exe by absolute path so the loaders do not
    # depend on PATH inheritance. runtime_env defaults os_name to sys.platform,
    # so the exe name is whatever this test actually runs under (duckdb.exe on
    # Windows).
    exe = "duckdb.exe" if sys.platform == "win32" else "duckdb"
    assert env["DUCKDB_BIN"] == str(tmp_path / "duckdb" / "bin" / exe)
    # Loaders and the seeder run the duckdb CLI with DuckDB 2.0's agent mode off.
    assert env["DUCKDB_AGENT_MODE"] == "0"


def test_runtime_env_pins_duckdb_bin_windows(tmp_path):
    # Windows regression: the prepended PATH dir does not reliably reach a
    # process the JVM starts, so DUCKDB_BIN (which the loaders read directly)
    # must point at duckdb.exe by absolute path.
    env = launcher.runtime_env(
        {"PATH": "C:\\Windows\\System32"},
        duckdb_bin=tmp_path / "duckdb" / "bin",
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=tmp_path / "duckdb" / "lib",
        os_name="win32",
    )
    assert env["DUCKDB_BIN"] == str(tmp_path / "duckdb" / "bin" / "duckdb.exe")


def test_runtime_env_windows_hands_the_jvm_its_duckdb_dll(tmp_path):
    # duckdb.dll is extracted into the lib dir: it must be both preloadable through
    # DUCKDB_CACHE_DIR and on PATH (the Windows DLL search has no loader variable).
    bin_dir, lib_dir = _duckdb_cache.plant(tmp_path, os_name="win32")
    # Split on the host's os.pathsep, so the base entry carries no drive colon.
    env = launcher.runtime_env(
        {"PATH": "/windows/system32"},
        duckdb_bin=bin_dir,
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=lib_dir,
        os_name="win32",
    )
    assert _duckdb_cache.preload_target(env, os_name="win32").is_file()
    entries = env["PATH"].split(os.pathsep)
    assert entries[:2] == [str(bin_dir), str(lib_dir)]
    assert entries[-1] == "/windows/system32"
    assert "DYLD_LIBRARY_PATH" not in env and "LD_LIBRARY_PATH" not in env


@pytest.mark.parametrize("command", [["serve", "--demo"], ["start", "--demo"]])
def test_demo_provisions_app_home_and_passes_env(runner, monkeypatch, tmp_path, command):
    from qod_cli.commands import demo as demo_cmd
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    sh = tmp_path / "scripts" / "spawn-quack-node.sh"
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "default_cache_dir", lambda: tmp_path / "cache")
    # The real ensure_* over a provisioned cache: the demo must provision libduckdb too.
    bin_dir, lib_dir = _duckdb_cache.plant(tmp_path / "cache")
    # On Windows the demo also provisions the release's qod-node.exe; stand one in.
    node_exe = tmp_path / "qod-node.exe"
    node_exe.write_text("")
    monkeypatch.setattr(launcher, "ensure_windows_qod_node", lambda *a, **k: node_exe)
    monkeypatch.setattr(
        launcher,
        "materialize_spawn_scripts",
        lambda dest: (sh, tmp_path / "scripts" / "spawn-quack-node.ps1"),
    )
    captured = {}

    def fake_exec(cmd, env):
        captured["cmd"], captured["env"] = cmd, env

    monkeypatch.setattr(demo_cmd, "_exec", fake_exec)
    with respx.mock:  # no routes: a provisioned cache must not hit the network
        result = runner.invoke(app, [*command, "--jar", str(jar)])
    assert result.exit_code == 0, result.output
    env = captured["env"]
    assert env["QOD_SPAWN_SCRIPT"] == str(sh)
    assert str(bin_dir) in env["PATH"]
    # The manager preloads libduckdb by absolute path, immune to SIP stripping DYLD_*.
    assert _duckdb_cache.preload_target(env) == lib_dir / _duckdb_cache.LIB_NAMES[sys.platform]
    assert _duckdb_cache.preload_target(env).is_file()


def test_demo_honors_qod_version_env(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "quack-on-demand-assembly-0.4.0.jar"
    jar.write_text("")
    asked = {}

    def fake_ensure_jar(version, cache_dir=None):
        asked["version"] = version
        return jar

    monkeypatch.setenv("QOD_VERSION", "0.4.0")
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "ensure_jar", fake_ensure_jar)
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo"])
    assert result.exit_code == 0, result.output
    assert asked["version"] == "0.4.0"


def test_demo_rejects_non_release_version_with_hint(runner, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["start", "--demo", "--version", "BUILD"])
    assert result.exit_code == 1
    assert "--jar" in result.output


def test_libduckdb_url_platform_mapping(monkeypatch):
    monkeypatch.delenv("DUCKDB_VERSION", raising=False)
    base = f"https://github.com/duckdb/duckdb/releases/download/v{launcher.DUCKDB_CLI_VERSION}"
    assert launcher.libduckdb_url("darwin", "arm64") == f"{base}/duckdb-shared-libs-osx-universal.tar.gz"
    assert launcher.libduckdb_url("linux", "x86_64") == f"{base}/duckdb-shared-libs-linux-amd64.tar.gz"
    assert launcher.libduckdb_url("win32", "ARM64") == f"{base}/duckdb-shared-libs-windows-arm64.tar.gz"


def _libduckdb_archive(name):
    return _tar_gz(name, "not really a dylib")


@respx.mock
def test_ensure_libduckdb_downloads_and_extracts(tmp_path):
    respx.get(launcher.libduckdb_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_libduckdb_archive("libduckdb.so"))
    )
    lib_dir = launcher.ensure_libduckdb(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert (pathlib.Path(lib_dir) / "libduckdb.so").is_file()


def test_ensure_libduckdb_reuses_cached(tmp_path):
    _, lib_dir = _duckdb_cache.plant(tmp_path, os_name="darwin")
    with respx.mock:  # no routes: reuse must not hit the network
        got = launcher.ensure_libduckdb(cache_dir=tmp_path, os_name="darwin", arch="arm64")
    assert pathlib.Path(got) == lib_dir == tmp_path / "duckdb" / launcher.DUCKDB_CLI_VERSION / "lib"


@respx.mock
def test_ensure_libduckdb_does_not_reuse_another_versions_cache(tmp_path):
    # A 1.5.x libduckdb lacks symbols the 2.0 libquackwire binds at load: reusing it refuses boot.
    _plant_legacy_and_other_version(tmp_path)
    route = respx.get(launcher.libduckdb_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_libduckdb_archive("libduckdb.so"))
    )
    lib_dir = launcher.ensure_libduckdb(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert route.called
    assert pathlib.Path(lib_dir) == tmp_path / "duckdb" / launcher.DUCKDB_CLI_VERSION / "lib"
    assert (pathlib.Path(lib_dir) / "libduckdb.so").read_text() != "1.5.6"


def test_runtime_env_adds_library_path_darwin(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin"},
        duckdb_bin=tmp_path / "duckdb" / "bin",
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=tmp_path / "duckdb" / "lib",
        os_name="darwin",
    )
    assert env["DYLD_LIBRARY_PATH"].startswith(str(tmp_path / "duckdb" / "lib"))


@pytest.mark.parametrize("os_name", ["darwin", "linux", "win32"])
def test_runtime_env_exports_the_versioned_cache_root_for_the_preload(tmp_path, os_name):
    # DUCKDB_CACHE_DIR, not the loader path, is what reaches a SIP-protected /usr/bin/java.
    bin_dir, lib_dir = _duckdb_cache.plant(tmp_path, os_name=os_name)
    env = launcher.runtime_env(
        {"PATH": "/usr/bin", "DUCKDB_CACHE_DIR": "/somewhere/else"},
        duckdb_bin=bin_dir,
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=lib_dir,
        os_name=os_name,
    )
    assert env["DUCKDB_CACHE_DIR"] == str(tmp_path / "duckdb")
    assert _duckdb_cache.preload_target(env, os_name=os_name).is_file()


@pytest.mark.skipif(
    sys.platform == "win32",
    reason="simulates linux's os_name mapping while joining with the host's real "
    "os.pathsep (';' on Windows, not the literal ':' this test pins); Windows has "
    "no LD_LIBRARY_PATH equivalent, see runtime_env's os_name-to-var mapping",
)
def test_runtime_env_adds_library_path_linux(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin", "LD_LIBRARY_PATH": "/opt/lib"},
        duckdb_bin=tmp_path / "duckdb" / "bin",
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=tmp_path / "duckdb" / "lib",
        os_name="linux",
    )
    assert env["LD_LIBRARY_PATH"] == str(tmp_path / "duckdb" / "lib") + ":/opt/lib"


def test_java_bin_env_overrides_detection(monkeypatch):
    from qod_cli.commands import _launch

    monkeypatch.setenv("JAVA_BIN", "/custom/java")
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    assert _launch.resolve_java() == "/custom/java"


def test_build_jar_command_splices_java_opts():
    cmd = launcher.build_jar_command(
        "/usr/bin/java", "/cache/qod.jar", ["demo"], java_opts="-Xmx2g -Dfoo=bar"
    )
    assert cmd == [
        "/usr/bin/java",
        "-Xmx2g",
        "-Dfoo=bar",
        "-Darrow.allocation.manager.type=Unsafe",
        "-jar",
        "/cache/qod.jar",
        "demo",
    ]


def test_qod_version_latest_forces_latest_release(monkeypatch):
    from qod_cli.commands import _launch

    monkeypatch.setattr(launcher, "latest_release_version", lambda: "0.5.0")
    asked = {}
    monkeypatch.setattr(launcher, "ensure_jar", lambda v, cache_dir=None: asked.setdefault("v", v))
    _launch.resolve_jar("latest")
    assert asked["v"] == "0.5.0"


def test_no_version_defaults_to_latest_release_even_on_a_release_cli(monkeypatch):
    from qod_cli.commands import _launch

    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.3.9")
    monkeypatch.setattr(launcher, "latest_release_version", lambda: "0.5.0")
    asked = {}
    monkeypatch.setattr(launcher, "ensure_jar", lambda v, cache_dir=None: asked.setdefault("v", v))
    _launch.resolve_jar(None)
    assert asked["v"] == "0.5.0"


def test_no_version_falls_back_to_stamped_manager_version_when_latest_lookup_fails(
    tmp_path, monkeypatch, capsys
):
    from qod_cli.commands import _launch

    def boom():
        raise httpx.ConnectError("offline")

    # The pinned release wins over a newer cached jar, but only because it is
    # itself cached: offline, an uncached pin is unrunnable (see
    # test_offline_jar_fallback.py for the full fallback contract).
    for v in ("0.3.9", "0.5.0"):
        (tmp_path / launcher.jar_name(v)).write_bytes(b"jar")
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.3.9")
    monkeypatch.setattr(launcher, "latest_release_version", boom)
    asked = {}
    monkeypatch.setattr(launcher, "ensure_jar", lambda v, cache_dir=None: asked.setdefault("v", v))
    _launch.resolve_jar(None)
    assert asked["v"] == "0.3.9"
    assert "0.3.9" in capsys.readouterr().err


def test_no_version_dev_build_errors_when_latest_lookup_fails_and_cache_is_empty(
    tmp_path, monkeypatch
):
    import typer

    from qod_cli.commands import _launch

    def boom():
        raise httpx.ConnectError("offline")

    # A dev build pins no release; with nothing cached either there is simply
    # nothing to run. (With a cached jar it now starts that one instead.)
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.3.10.dev0")
    monkeypatch.setattr(launcher, "latest_release_version", boom)
    with pytest.raises(typer.Exit):
        _launch.resolve_jar(None)


def test_jar_cache_dir_env_overrides_cache(tmp_path, monkeypatch):
    monkeypatch.setenv("JAR_CACHE_DIR", str(tmp_path / "jarcache"))
    (tmp_path / "jarcache").mkdir()
    (tmp_path / "jarcache" / JAR_037).write_bytes(b"cached")
    with respx.mock:  # cache hit must not touch the network
        assert launcher.ensure_jar("0.3.7") == tmp_path / "jarcache" / JAR_037


def test_duckdb_version_env_overrides_pin(monkeypatch):
    monkeypatch.setenv("DUCKDB_VERSION", "1.6.0")
    assert "v1.6.0/duckdb-cli-" in launcher.duckdb_cli_url("linux", "x86_64")
    assert "v1.6.0/duckdb-shared-libs-" in launcher.libduckdb_url("linux", "x86_64")


def test_duckdb_cache_dir_env_uses_run_jar_layout(tmp_path, monkeypatch):
    monkeypatch.setenv("DUCKDB_CACHE_DIR", str(tmp_path / "dcache"))
    duckdb = tmp_path / "dcache" / launcher.duckdb_version() / "bin" / "duckdb"
    duckdb.parent.mkdir(parents=True)
    duckdb.write_text("")
    duckdb.chmod(0o755)
    with respx.mock:  # pre-populated cache must not touch the network
        bin_dir = launcher.ensure_duckdb_cli(os_name="linux", arch="x86_64")
    assert pathlib.Path(bin_dir) == duckdb.parent


def test_default_cache_and_duckdb_cache_dir_share_one_layout(tmp_path, monkeypatch):
    # <root>/<version>/{bin,lib} either way, so runtime_env can hand <root> to the preload.
    lib_dir = launcher._duckdb_dir("lib", tmp_path)
    assert lib_dir == launcher.duckdb_cache_root(tmp_path) / launcher.DUCKDB_CLI_VERSION / "lib"
    assert launcher.duckdb_cache_root(tmp_path) == tmp_path / "duckdb"
    monkeypatch.setenv("DUCKDB_CACHE_DIR", str(tmp_path / "dcache"))
    assert launcher.duckdb_cache_root(tmp_path) == tmp_path / "dcache"
    assert launcher._duckdb_dir("lib", tmp_path) == tmp_path / "dcache" / launcher.DUCKDB_CLI_VERSION / "lib"
    monkeypatch.setenv("DUCKDB_CACHE_DIR", "  ")
    assert launcher.duckdb_cache_root(tmp_path) == tmp_path / "duckdb"
