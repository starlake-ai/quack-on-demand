"""Ctrl-C on `qod start` / `qod start --demo` acts as `qod stop`.

The launcher supervises the manager JVM instead of exec-ing it: the child runs
in its own session so SIGINT from the terminal lands on the CLI alone, and the
CLI answers by running the `qod stop` teardown (SIGTERM manager + nodes,
bounded wait, SIGKILL escalation) rather than letting a group-wide SIGINT
orphan the duckdb node processes.
"""

import os
import signal
import subprocess
import sys
import threading
import time

import pytest

from qod_cli.commands import _launch

pytestmark = pytest.mark.skipif(
    sys.platform == "win32", reason="POSIX process supervision only"
)


def test_exit_code_propagates():
    code = _launch._run_supervised(
        [sys.executable, "-c", "import sys; sys.exit(7)"],
        dict(os.environ),
        teardown=lambda: None,
    )
    assert code == 7


def test_child_runs_in_its_own_session(tmp_path):
    out = tmp_path / "sid"
    code = _launch._run_supervised(
        [
            sys.executable,
            "-c",
            f"import os; open({str(out)!r}, 'w').write(str(os.getsid(0)))",
        ],
        dict(os.environ),
        teardown=lambda: None,
    )
    assert code == 0
    assert int(out.read_text()) != os.getsid(0)


def test_child_never_holds_a_terminal_fd(tmp_path):
    # A JVM with a tty fd can flip the terminal into raw mode (ISIG off),
    # after which Ctrl-C stops generating SIGINT and the supervisor is deaf.
    out = tmp_path / "ttys"
    code = _launch._run_supervised(
        [
            sys.executable,
            "-c",
            "import os, sys; open(sys.argv[1], 'w').write("
            "','.join(str(os.isatty(fd)) for fd in (0, 1, 2)))",
            str(out),
        ],
        dict(os.environ),
        teardown=lambda: None,
    )
    assert code == 0
    assert out.read_text() == "False,False,False"


def test_sigint_runs_teardown_and_reaps_child():
    called = threading.Event()
    marker = f"qod-supervise-test-{os.getpid()}"

    def interrupt_soon():
        time.sleep(0.5)
        os.kill(os.getpid(), signal.SIGINT)

    threading.Thread(target=interrupt_soon, daemon=True).start()
    code = _launch._run_supervised(
        [sys.executable, "-c", f"import time; time.sleep(60)  # {marker}", marker],
        dict(os.environ),
        teardown=called.set,
    )
    assert called.is_set()
    assert code == 130
    # The safety net reaped the child even though the (fake) teardown did not.
    leftovers = subprocess.run(
        ["pgrep", "-f", marker], capture_output=True, text=True
    ).stdout.split()
    assert leftovers == []


def test_default_teardown_is_qod_stop(monkeypatch):
    from qod_cli.commands import stop as stop_cmd

    called = threading.Event()
    monkeypatch.setattr(stop_cmd, "perform_stop", called.set)

    def interrupt_soon():
        time.sleep(0.5)
        os.kill(os.getpid(), signal.SIGINT)

    threading.Thread(target=interrupt_soon, daemon=True).start()
    code = _launch._run_supervised(
        [sys.executable, "-c", "import time; time.sleep(60)"],
        dict(os.environ),
    )
    assert called.is_set()
    assert code == 130
