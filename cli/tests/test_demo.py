import pathlib
import hashlib

import httpx
import respx
import pytest

from qod_cli import launcher

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


def test_find_java_falls_back_to_path(monkeypatch):
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.setattr(launcher, "_which_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "java_major", lambda p: 21)
    assert launcher.find_java() == "/usr/bin/java"


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
                    "binaries": [
                        {
                            "package": {
                                "link": pkg_url,
                                "checksum": hashlib.sha256(tarball).hexdigest(),
                            }
                        }
                    ]
                }
            ],
        )
    )
    respx.get(pkg_url).mock(return_value=httpx.Response(200, content=tarball))
    java = launcher.ensure_jre(cache_dir=tmp_path / "jre", os_name="linux", arch="x86_64")
    assert java.endswith("bin/java")
    assert (tmp_path / "jre").as_posix() in java


@respx.mock
def test_ensure_jre_checksum_mismatch_refuses(tmp_path, monkeypatch):
    monkeypatch.setattr(launcher.sys, "platform", "linux")
    tarball = _jre_tarball(tmp_path)
    pkg_url = "https://example.com/temurin-jre.tar.gz"
    respx.get(launcher.adoptium_assets_url("linux", "x86_64")).mock(
        return_value=httpx.Response(
            200,
            json=[{"binaries": [{"package": {"link": pkg_url, "checksum": "0" * 64}}]}],
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
    result = runner.invoke(app, ["demo", "--jar", str(jar)])
    assert result.exit_code == 0, result.output
    assert captured["cmd"] == ["/usr/bin/java", ARROW_FLAG, "-jar", str(jar), "demo"]


def test_demo_passes_extra_args_to_the_jar(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    captured = _capture_exec(monkeypatch)
    result = runner.invoke(app, ["demo", "--jar", str(jar), "--sf", "1"])
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
    result = runner.invoke(app, ["demo"])  # CLI __version__ is a .dev0 build
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
    result = runner.invoke(app, ["demo", "--version", "0.9.9"])
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
    result = runner.invoke(app, ["demo", "--jar", str(jar), "--yes"])
    assert result.exit_code == 0, result.output
    assert captured["cmd"][0] == "/cache/jre/bin/java"


def test_demo_without_java_on_musl_fails_with_apk_hint(runner, monkeypatch, tmp_path):
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    monkeypatch.setattr(launcher, "find_java", lambda: None)
    monkeypatch.setattr(launcher, "is_musl", lambda root=None: True)
    result = runner.invoke(app, ["demo", "--jar", str(jar), "--yes"])
    assert result.exit_code == 1
    assert "apk add" in result.output


def test_demo_integrity_error_refuses_with_message(runner, monkeypatch):
    from qod_cli.main import app

    def bad_jar(version, cache_dir=None):
        raise launcher.IntegrityError("sha256 mismatch for x")

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "ensure_jar", bad_jar)
    result = runner.invoke(app, ["demo", "--version", "0.9.9"])
    assert result.exit_code == 1
    assert "refusing to run" in result.output


def test_demo_download_failure_suggests_local_jar(runner, monkeypatch):
    from qod_cli.main import app

    def down(version, cache_dir=None):
        raise httpx.ConnectError("boom")

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "ensure_jar", down)
    result = runner.invoke(app, ["demo", "--version", "0.9.9"])
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
    result = runner.invoke(app, ["demo", "--version", "0.3.7"])
    assert result.exit_code == 1
    assert "0.3.8" in result.output


def test_demo_refuses_when_latest_release_predates_the_demo(runner, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(launcher, "latest_release_version", lambda: "0.3.7")
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["demo"])
    assert result.exit_code == 1
    assert "0.3.8" in result.output


def test_duckdb_cli_url_platform_mapping():
    base = "https://github.com/duckdb/duckdb/releases/download/v1.5.4"
    assert launcher.duckdb_cli_url("darwin", "arm64") == f"{base}/duckdb_cli-osx-universal.zip"
    assert launcher.duckdb_cli_url("linux", "x86_64") == f"{base}/duckdb_cli-linux-amd64.zip"
    assert launcher.duckdb_cli_url("linux", "aarch64") == f"{base}/duckdb_cli-linux-arm64.zip"
    assert launcher.duckdb_cli_url("win32", "AMD64") == f"{base}/duckdb_cli-windows-amd64.zip"


def _duckdb_zip():
    import io
    import zipfile

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("duckdb", "#!/bin/sh\n")
    return buf.getvalue()


@respx.mock
def test_ensure_duckdb_cli_downloads_and_extracts(tmp_path):
    respx.get(launcher.duckdb_cli_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_duckdb_zip())
    )
    bin_dir = launcher.ensure_duckdb_cli(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    duckdb = pathlib.Path(bin_dir) / "duckdb"
    assert duckdb.is_file()
    assert duckdb.stat().st_mode & 0o111


def test_ensure_duckdb_cli_reuses_cached(tmp_path):
    duckdb = tmp_path / "duckdb" / "bin" / "duckdb"
    duckdb.parent.mkdir(parents=True)
    duckdb.write_text("")
    duckdb.chmod(0o755)
    with respx.mock:  # no routes: reuse must not hit the network
        bin_dir = launcher.ensure_duckdb_cli(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert pathlib.Path(bin_dir) == duckdb.parent


def test_bundled_spawn_scripts_match_the_canonical_ones():
    repo_scripts = pathlib.Path(__file__).resolve().parents[2] / "scripts"
    for name in ("spawn-quack-node.sh", "spawn-quack-node.ps1"):
        bundled = launcher.bundled_spawn_script(name)
        assert bundled.read_bytes() == (repo_scripts / name).read_bytes(), (
            f"{name} drifted: re-copy scripts/{name} into cli/src/qod_cli/scripts/"
        )


def test_materialize_spawn_scripts_are_executable(tmp_path):
    sh, ps1 = launcher.materialize_spawn_scripts(tmp_path)
    assert sh.name == "spawn-quack-node.sh" and sh.stat().st_mode & 0o111
    assert ps1.name == "spawn-quack-node.ps1"


def test_demo_env_wires_spawn_script_duckdb_and_app_home(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin"},
        app_home=tmp_path,
        duckdb_bin=tmp_path / "duckdb" / "bin",
        spawn_sh=tmp_path / "scripts" / "spawn-quack-node.sh",
        spawn_ps1=tmp_path / "scripts" / "spawn-quack-node.ps1",
    )
    assert env["QOD_SPAWN_SCRIPT"] == str(tmp_path / "scripts" / "spawn-quack-node.sh")
    assert env["QOD_SPAWN_SCRIPT_WINDOWS"] == str(tmp_path / "scripts" / "spawn-quack-node.ps1")
    assert env["QOD_APP_HOME"] == str(tmp_path)
    assert env["PATH"].startswith(str(tmp_path / "duckdb" / "bin"))
    assert env["PATH"].endswith("/usr/bin")


def test_demo_provisions_app_home_and_passes_env(runner, monkeypatch, tmp_path):
    from qod_cli.commands import demo as demo_cmd
    from qod_cli.main import app

    jar = tmp_path / "qod.jar"
    jar.write_text("")
    sh = tmp_path / "scripts" / "spawn-quack-node.sh"
    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    monkeypatch.setattr(
        launcher, "ensure_duckdb_cli", lambda cache_dir=None, **kw: tmp_path / "duckdb" / "bin"
    )
    monkeypatch.setattr(
        launcher,
        "materialize_spawn_scripts",
        lambda dest: (sh, tmp_path / "scripts" / "spawn-quack-node.ps1"),
    )
    captured = {}

    def fake_exec(cmd, env):
        captured["cmd"], captured["env"] = cmd, env

    monkeypatch.setattr(demo_cmd, "_exec", fake_exec)
    result = runner.invoke(app, ["demo", "--jar", str(jar)])
    assert result.exit_code == 0, result.output
    assert captured["env"]["QOD_SPAWN_SCRIPT"] == str(sh)
    assert str(tmp_path / "duckdb" / "bin") in captured["env"]["PATH"]


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
    result = runner.invoke(app, ["demo"])
    assert result.exit_code == 0, result.output
    assert asked["version"] == "0.4.0"


def test_demo_rejects_non_release_version_with_hint(runner, monkeypatch):
    from qod_cli.main import app

    monkeypatch.setattr(launcher, "find_java", lambda: "/usr/bin/java")
    _capture_exec(monkeypatch)
    result = runner.invoke(app, ["demo", "--version", "BUILD"])
    assert result.exit_code == 1
    assert "--jar" in result.output


def test_libduckdb_url_platform_mapping():
    base = "https://github.com/duckdb/duckdb/releases/download/v1.5.4"
    assert launcher.libduckdb_url("darwin", "arm64") == f"{base}/libduckdb-osx-universal.zip"
    assert launcher.libduckdb_url("linux", "x86_64") == f"{base}/libduckdb-linux-amd64.zip"


def _libduckdb_zip(name):
    import io
    import zipfile

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(name, "not really a dylib")
    return buf.getvalue()


@respx.mock
def test_ensure_libduckdb_downloads_and_extracts(tmp_path):
    respx.get(launcher.libduckdb_url("linux", "x86_64")).mock(
        return_value=httpx.Response(200, content=_libduckdb_zip("libduckdb.so"))
    )
    lib_dir = launcher.ensure_libduckdb(cache_dir=tmp_path, os_name="linux", arch="x86_64")
    assert (pathlib.Path(lib_dir) / "libduckdb.so").is_file()


def test_ensure_libduckdb_reuses_cached(tmp_path):
    lib = tmp_path / "duckdb" / "lib" / "libduckdb.dylib"
    lib.parent.mkdir(parents=True)
    lib.write_text("")
    with respx.mock:  # no routes: reuse must not hit the network
        lib_dir = launcher.ensure_libduckdb(cache_dir=tmp_path, os_name="darwin", arch="arm64")
    assert pathlib.Path(lib_dir) == lib.parent


def test_runtime_env_adds_library_path_darwin(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin"},
        app_home=tmp_path,
        duckdb_bin=tmp_path / "duckdb" / "bin",
        spawn_sh=tmp_path / "s.sh",
        spawn_ps1=tmp_path / "s.ps1",
        libduckdb_lib=tmp_path / "duckdb" / "lib",
        os_name="darwin",
    )
    assert env["DYLD_LIBRARY_PATH"].startswith(str(tmp_path / "duckdb" / "lib"))


def test_runtime_env_adds_library_path_linux(tmp_path):
    env = launcher.runtime_env(
        {"PATH": "/usr/bin", "LD_LIBRARY_PATH": "/opt/lib"},
        app_home=tmp_path,
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
    assert _launch.resolve_java(yes=True) == "/custom/java"


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
    monkeypatch, capsys
):
    from qod_cli.commands import _launch

    def boom():
        raise httpx.ConnectError("offline")

    monkeypatch.setattr(_launch, "MANAGER_VERSION", "0.3.9")
    monkeypatch.setattr(launcher, "latest_release_version", boom)
    asked = {}
    monkeypatch.setattr(launcher, "ensure_jar", lambda v, cache_dir=None: asked.setdefault("v", v))
    _launch.resolve_jar(None)
    assert asked["v"] == "0.3.9"
    assert "0.3.9" in capsys.readouterr().err


def test_no_version_dev_build_errors_when_latest_lookup_fails(monkeypatch):
    import typer

    from qod_cli.commands import _launch

    def boom():
        raise httpx.ConnectError("offline")

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
    assert "v1.6.0/duckdb_cli-" in launcher.duckdb_cli_url("linux", "x86_64")
    assert "v1.6.0/libduckdb-" in launcher.libduckdb_url("linux", "x86_64")


def test_duckdb_cache_dir_env_uses_run_jar_layout(tmp_path, monkeypatch):
    monkeypatch.setenv("DUCKDB_CACHE_DIR", str(tmp_path / "dcache"))
    duckdb = tmp_path / "dcache" / launcher.duckdb_version() / "bin" / "duckdb"
    duckdb.parent.mkdir(parents=True)
    duckdb.write_text("")
    duckdb.chmod(0o755)
    with respx.mock:  # pre-populated cache must not touch the network
        bin_dir = launcher.ensure_duckdb_cli(os_name="linux", arch="x86_64")
    assert pathlib.Path(bin_dir) == duckdb.parent
