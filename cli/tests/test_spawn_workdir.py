"""The bundled spawn script (what `qod fleet join` runs) starts qod-node in an empty private directory.

DuckDB 2.0 replacement-scans an unquoted dotted name as a file (`SELECT * FROM x.csv` reads ./x.csv),
and an unquoted name cannot hold a path separator, so the node's working directory is the one place
that form reaches. The script moves qod-node into a fresh 0700 directory of its own, makes the paths
the node relies on absolute against the directory it started in, and removes the directory when the
node stops.
"""

import os
import pathlib
import re
import stat
import subprocess
import sys
import time

import pytest

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="bash spawn script")

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "src" / "qod_cli" / "scripts" / "spawn-quack-node.sh"
TEMP_DIR_RE = re.compile(r"^SET temp_directory = '([^']+)';$", re.M)


def _env(tmp_path, **extra):
    from qod_cli.fleet_join import write_secrets_dir
    d = write_secrets_dir(tmp_path / "secrets", {"QOD_NODE_TOKEN": "tok-file"})
    tmpdir = tmp_path / "tmpdir"
    tmpdir.mkdir(exist_ok=True)
    env = {"PATH": os.environ["PATH"], "TMPDIR": str(tmpdir), "kind": "memory", "dbName": "m",
           "schemaName": "main", "QOD_SECRETS_DIR": str(d), "QOD_SECRETS_DIR_EPHEMERAL": "1",
           "QOD_NODE_ID": "quack-workdir-1"}
    env.update(extra)
    return env, tmpdir


def test_a_relative_data_path_resolves_against_the_starting_directory(tmp_path):
    start = tmp_path / "start"
    start.mkdir()
    env, tmpdir = _env(tmp_path, QOD_SPAWN_DRY_RUN="1", kind="duckdb-file", dataPath="files/a.duckdb")
    out = subprocess.run(["bash", str(SCRIPT), "21999"], cwd=start, env=env, capture_output=True,
                         text=True, check=True).stdout
    assert f"ATTACH '{start.resolve() / 'files' / 'a.duckdb'}' AS \"m\";" in out
    spill = TEMP_DIR_RE.search(out).group(1)
    assert os.path.isabs(spill) and not os.path.exists(spill)
    assert list(tmpdir.iterdir()) == []


def test_qod_node_runs_in_an_empty_private_directory_removed_on_stop(tmp_path):
    start = tmp_path / "start"
    start.mkdir()
    (start / "x.csv").write_text("a,b\n1,2\n")
    probe = tmp_path / "probe"
    probe.mkdir()
    fake = tmp_path / "bin" / "qod-node"
    fake.parent.mkdir()
    fake.write_text(
        "#!/bin/sh\n"
        f"pwd -P > '{probe}/cwd.part'\n"
        f"ls -A | wc -l | tr -d ' ' > '{probe}/entries'\n"
        f"mv '{probe}/cwd.part' '{probe}/cwd'\n"
        f"cat > '{probe}/init'\n"
        "trap 'exit 143' TERM INT\n"
        "while :; do sleep 1; done\n"
    )
    fake.chmod(0o700)
    env, tmpdir = _env(tmp_path, QOD_NODE_BIN=str(fake))
    proc = subprocess.Popen(["bash", str(SCRIPT), "23353"], cwd=start, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 20
        while not (probe / "cwd").exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        cwd = pathlib.Path((probe / "cwd").read_text().strip())
        assert cwd != start.resolve()
        assert (probe / "entries").read_text().strip() == "0"
        assert stat.S_IMODE(cwd.stat().st_mode) == 0o700
    finally:
        proc.terminate()
        proc.wait(timeout=20)
    assert not cwd.exists()
    assert list(tmpdir.iterdir()) == []
