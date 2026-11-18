"""deploy/host (#220): install.sh, switch.sh and the unit, run against a temporary prefix with a fake
Python (whose venv holds a fake pip and qod) and a fake systemctl. Nothing here runs as root,
creates a user or touches /opt, /etc or systemd."""

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="the host layout is Linux/systemd")

HOST = Path(__file__).resolve().parents[2] / "deploy" / "host"
INSTALL = HOST / "install.sh"
SWITCH = HOST / "switch.sh"
UNIT = HOST / "qod-manager.service"

# A venv whose pip "installs" a qod that reports its version and logs each start to $HOME/calls.log
# with which variables reached it (booleans or test values only, never an environment dump).
FAKE_PYTHON = r"""#!/bin/sh
[ "$1" = "-m" ] && [ "$2" = "venv" ] || exit 9
dir="$3"
mkdir -p "$dir/bin"
cat >"$dir/bin/pip" <<'PIP'
#!/bin/sh
here="$(cd "$(dirname "$0")" && pwd)"
echo "$*" >>"$here/../pip.log"
for a in "$@"; do case "$a" in qod==*) v="${a#qod==}";; esac; done
[ "$v" = "9.9.9" ] && { echo "ERROR: No matching distribution found for qod==9.9.9" >&2; exit 1; }
cat >"$here/qod" <<QOD
#!/bin/sh
if [ "\$1" = "--version" ]; then echo "qod $v"; exit 0; fi
echo "\$* proxy=\${HTTPS_PROXY:-} pg=\${QOD_PG_PASSWORD:+set} leak=\${LEAK:+set}" >>"\$HOME/calls.log"
[ "$v" = "8.8.8" ] && { echo "could not download the jar" >&2; exit 1; }
exit 0
QOD
chmod +x "$here/qod"
PIP
chmod +x "$dir/bin/pip"
"""

FAKE_SYSTEMCTL = """#!/bin/sh
echo "$*" >>"$SYSTEMCTL_LOG"
"""


@pytest.fixture
def host(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    python = bin_dir / "python3"
    python.write_text(FAKE_PYTHON)
    python.chmod(0o755)
    systemctl = bin_dir / "systemctl"
    systemctl.write_text(FAKE_SYSTEMCTL)
    systemctl.chmod(0o755)
    home = tmp_path / "home"
    home.mkdir()
    etc = tmp_path / "etc"
    etc.mkdir()
    (etc / "qod.env").write_text(
        'QOD_PG_PASSWORD=not-for-the-download\nHTTPS_PROXY="http://proxy.internal:3128"\n'
    )
    env = {
        **os.environ,
        "QOD_HOST_PREFIX": str(tmp_path / "opt"),
        "QOD_HOST_ETC": str(etc),
        "QOD_HOST_USER": subprocess.run(["id", "-un"], capture_output=True, text=True).stdout.strip(),
        "QOD_HOST_HOME": str(home),
        "PYTHON": str(python),
        "SYSTEMCTL": str(systemctl),
        "SYSTEMCTL_LOG": str(tmp_path / "systemctl.log"),
        "LEAK": "1",
    }

    class Host:
        prefix = tmp_path / "opt"
        pin = etc / "version.env"
        calls = home / "calls.log"
        restarts = tmp_path / "systemctl.log"

        def run(self, script, *args):
            return subprocess.run(
                ["bash", str(script), *args], env=env, capture_output=True, text=True, timeout=60
            )

        def lines(self, path):
            return path.read_text().splitlines() if path.exists() else []

    return Host()


def test_the_scripts_parse():
    for script in (INSTALL, SWITCH):
        assert subprocess.run(["bash", "-n", str(script)]).returncode == 0
        assert os.access(script, os.X_OK), f"{script.name} is not executable"


@pytest.mark.skipif(shutil.which("shellcheck") is None, reason="shellcheck is not installed")
def test_the_scripts_are_shellcheck_clean():
    proc = subprocess.run(["shellcheck", str(INSTALL), str(SWITCH)], capture_output=True, text=True)
    assert proc.returncode == 0, proc.stdout


def test_install_installs_caches_and_pins_the_first_version(host):
    result = host.run(INSTALL, "1.2.3")
    assert result.returncode == 0, result.stderr
    assert (host.prefix / "versions" / "1.2.3" / ".qod-installed").is_file()
    assert host.pin.read_text() == "QOD_VERSION=1.2.3\n"
    # As the service user, with the download-relevant keys of qod.env and nothing else.
    assert host.lines(host.calls) == [
        "start --version 1.2.3 --download-only proxy=http://proxy.internal:3128 pg= leak="
    ]


def test_install_again_only_checks_the_cache_and_leaves_other_versions(host):
    assert host.run(INSTALL, "1.2.3").returncode == 0
    assert host.run(INSTALL, "1.2.4").returncode == 0
    again = host.run(INSTALL, "1.2.3")
    assert again.returncode == 0, again.stderr
    assert "is installed" in again.stdout
    assert len(host.lines(host.prefix / "versions" / "1.2.3" / "pip.log")) == 1
    assert len(host.lines(host.calls)) == 3
    assert host.pin.read_text() == "QOD_VERSION=1.2.3\n"  # a later install never moves the pin
    assert (host.prefix / "versions" / "1.2.4" / ".qod-installed").is_file()


def test_install_refuses_an_unknown_release_and_keeps_nothing(host):
    result = host.run(INSTALL, "9.9.9")
    assert result.returncode == 1
    assert "could not install qod==9.9.9" in result.stderr
    assert not (host.prefix / "versions" / "9.9.9").exists()
    assert not host.pin.exists()


def test_install_keeps_nothing_when_the_download_fails(host):
    result = host.run(INSTALL, "8.8.8")
    assert result.returncode == 1
    assert "could not cache" in result.stderr
    assert not (host.prefix / "versions" / "8.8.8").exists()
    assert not host.pin.exists()


@pytest.mark.parametrize("bad", ["latest", "", "1.2.3;id", "../1.2", "1..2", "1.2.3.dev0"])
def test_install_and_switch_refuse_what_is_not_a_release(host, bad):
    for script in (INSTALL, SWITCH):
        result = host.run(script, bad)
        assert result.returncode == 2, (script.name, bad)
    assert not (host.prefix / "versions").exists() or list((host.prefix / "versions").iterdir()) == []


def test_switch_refuses_a_version_not_installed(host):
    assert host.run(INSTALL, "1.2.3").returncode == 0
    result = host.run(SWITCH, "1.2.4")
    assert result.returncode == 1
    assert "not installed" in result.stderr and "1.2.3" in result.stderr
    assert host.pin.read_text() == "QOD_VERSION=1.2.3\n"
    assert host.lines(host.restarts) == []


def test_switch_and_switch_back(host):
    assert host.run(INSTALL, "1.2.3").returncode == 0
    assert host.run(INSTALL, "1.2.4").returncode == 0
    up = host.run(SWITCH, "1.2.4")
    assert up.returncode == 0, up.stderr
    assert host.pin.read_text() == "QOD_VERSION=1.2.4\n"
    assert "to roll back: switch.sh 1.2.3" in up.stdout
    back = host.run(SWITCH, "1.2.3")
    assert back.returncode == 0, back.stderr
    assert host.pin.read_text() == "QOD_VERSION=1.2.3\n"
    assert host.lines(host.restarts) == ["restart qod-manager", "restart qod-manager"]
    # Each switch checked the cache of the version it moved to first.
    assert host.lines(host.calls)[-2:] == [
        "start --version 1.2.4 --download-only proxy=http://proxy.internal:3128 pg= leak=",
        "start --version 1.2.3 --download-only proxy=http://proxy.internal:3128 pg= leak=",
    ]


def test_switch_no_restart_writes_the_pin_only(host):
    assert host.run(INSTALL, "1.2.3").returncode == 0
    assert host.run(INSTALL, "1.2.4").returncode == 0
    result = host.run(SWITCH, "1.2.4", "--no-restart")
    assert result.returncode == 0, result.stderr
    assert host.pin.read_text() == "QOD_VERSION=1.2.4\n"
    assert host.lines(host.restarts) == []


def test_switch_keeps_the_pin_when_the_cache_cannot_be_completed(host):
    assert host.run(INSTALL, "1.2.3").returncode == 0
    # An installed version whose cache was wiped, on a host with no network.
    broken = host.prefix / "versions" / "8.8.8"
    shutil.copytree(host.prefix / "versions" / "1.2.3", broken)
    qod = broken / "bin" / "qod"
    qod.write_text(qod.read_text().replace('"qod 1.2.3"', '"qod 8.8.8"').replace("exit 0\n", "exit 1\n", 2))
    result = host.run(SWITCH, "8.8.8")
    assert result.returncode == 1
    assert "pin was not changed" in result.stderr
    assert host.pin.read_text() == "QOD_VERSION=1.2.3\n"
    assert host.lines(host.restarts) == []


def _unit():
    return dict(
        (key, value)
        for key, _, value in (
            line.partition("=") for line in UNIT.read_text().splitlines() if "=" in line and not line.startswith("#")
        )
        if key not in ("EnvironmentFile",)
    )


def test_the_unit_runs_the_pinned_cli_with_the_pinned_jar():
    text = UNIT.read_text()
    assert "EnvironmentFile=/etc/qod/version.env\n" in text
    # The guide's settings file, required: a missing one must not start on the built-in defaults.
    assert "EnvironmentFile=/etc/qod/qod.env\n" in text
    unit = _unit()
    assert unit["User"] == "qod"
    assert unit["KillMode"] == "mixed"
    # The published single-server guide's settings (deploy-single-server.md, section 0.3).
    assert unit["Restart"] == "always"
    assert unit["StartLimitIntervalSec"] == "0"
    assert unit["OOMPolicy"] == "continue"
    assert unit["NoNewPrivileges"] == "true"
    # The drain and exit (60 + 20), the nodes' stop (10 + 2), the wait for the JVM (10).
    assert int(unit["TimeoutStopSec"]) > 60 + 20 + 10 + 2 + 10
    assert unit["SuccessExitStatus"] == "130"
    assert (
        'exec /opt/qod/versions/"$$QOD_VERSION"/bin/qod start --version "$$QOD_VERSION"'
        in unit["ExecStart"]
    )


@pytest.mark.parametrize("pin", ["", "1.2;id", "../../bin"])
def test_the_unit_refuses_a_pin_that_is_not_a_release(pin):
    script = re.fullmatch(r"/bin/sh -c '(.*)'", _unit()["ExecStart"]).group(1).replace("$$", "$")
    proc = subprocess.run(
        ["/bin/sh", "-c", script], env={"PATH": "/usr/bin:/bin", "QOD_VERSION": pin},
        capture_output=True, text=True,
    )
    assert proc.returncode == 1
    assert "is not a release version" in proc.stderr
