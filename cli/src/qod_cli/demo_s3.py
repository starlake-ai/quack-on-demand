"""The demo on S3: `qod serve --demo --s3` and `--s3-path`.

The manager reads S3 keys only from its process environment, so the CLI settles everything here
and hands it to the jar as variables. `DemoS3.scala` is the other half of that contract.
"""

import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import typer

from . import launcher

LOCAL_BUCKET = "qod-demo"
READY_TIMEOUT_S = 60.0
FOLDER_PREFIX = "qod-demo-s3-"
PORT_NAMES = (
    "master", "master.grpc", "volume", "volume.grpc", "filer", "filer.grpc", "s3", "s3.grpc",
)
# Limited to what every reader of the path takes as it is; DemoS3.scala checks the same.
_S3_PATH = re.compile(r"^s3://[a-z0-9][a-z0-9.\-]{1,61}[a-z0-9](/[A-Za-z0-9._\-]+)*/?$")
_ENDPOINT = re.compile(r"^(https?://)?[A-Za-z0-9.\-]+(:[0-9]+)?/?$")
# All the store is given. WEED_* would reconfigure it, and it has no use for anyone's keys.
_STORE_ENV_KEYS = ("PATH", "HOME", "TMPDIR", "TEMP", "TMP", "SystemRoot")


class DemoS3Error(Exception):
    """A refusal or a failure, in words fit for the user."""


@dataclass(frozen=True)
class LocalRequest:
    """`--s3`: a throwaway store on this machine."""


@dataclass(frozen=True)
class BucketRequest:
    """`--s3-path`: the user's own bucket."""

    path: str
    access_key_id: str = field(repr=False)
    secret_access_key: str = field(repr=False)
    region: str
    endpoint: str
    use_ssl: bool
    url_style: str


def request(
    demo, s3, s3_path, endpoint, region, access_key_id, secret_access_key, environ
) -> "None | LocalRequest | BucketRequest":
    """Apply the flag rules. None means the plain demo, or no demo at all."""
    if not demo:
        if s3 or s3_path:
            raise DemoS3Error("--s3 and --s3-path only apply with --demo")
        return None
    if s3 and s3_path:
        raise DemoS3Error("--s3 starts a local store and --s3-path names a bucket: pick one")
    if not s3_path:
        if endpoint or region or access_key_id or secret_access_key:
            raise DemoS3Error(
                "--endpoint, --region, --access-key-id and --secret-access-key only apply to "
                "the demo with --s3-path"
            )
        return LocalRequest() if s3 else None
    if ".." in s3_path.split("/") or "." in s3_path.split("/") or not _S3_PATH.match(s3_path):
        raise DemoS3Error(
            f"--s3-path must be s3://<bucket>[/<prefix>], with letters, digits, '.', '_' and '-' "
            f"only: {s3_path}"
        )
    key = access_key_id or environ.get("AWS_ACCESS_KEY_ID")
    secret = secret_access_key or environ.get("AWS_SECRET_ACCESS_KEY")
    if not (key and secret):
        raise DemoS3Error(
            "--s3-path needs a key pair: --access-key-id and --secret-access-key, or "
            "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY"
        )
    keys_from_env = not (access_key_id and secret_access_key)
    if keys_from_env and environ.get("AWS_SESSION_TOKEN"):
        raise DemoS3Error(
            "AWS_SESSION_TOKEN is set, and the demo cannot use temporary credentials: "
            "use a long-lived key"
        )
    host, use_ssl, url_style = "", True, "vhost"
    if endpoint:
        if not _ENDPOINT.match(endpoint):
            raise DemoS3Error(f"--endpoint must be [http[s]://]host[:port]: {endpoint}")
        use_ssl = not endpoint.startswith("http://")
        host = endpoint.removeprefix("http://").removeprefix("https://").rstrip("/")
        url_style = "path"
    return BucketRequest(
        path=s3_path.rstrip("/"),
        access_key_id=key,
        secret_access_key=secret,
        region=region or environ.get("AWS_REGION") or environ.get("AWS_DEFAULT_REGION")
        or "us-east-1",
        endpoint=host,
        use_ssl=use_ssl,
        url_style=url_style,
    )


def _run_id() -> str:
    return secrets.token_hex(4)


def plain_env(base: dict) -> dict:
    """A plain demo: a stale path in the shell must not turn it into an S3 run."""
    return {k: v for k, v in base.items() if k not in ("QOD_DEMO_S3_PATH", "QOD_DEMO_S3_LOCAL")}


def _s3_env(base, path, local, key, secret, endpoint, region, url_style, use_ssl) -> dict:
    # Every variable, every time, so nothing ambient leaks in.
    env = {k: v for k, v in plain_env(base).items() if k != "QOD_S3_ENDPOINT"}
    env["QOD_DEMO_S3_PATH"] = path
    if local:
        env["QOD_DEMO_S3_LOCAL"] = "1"
    env["QOD_S3_ACCESS_KEY_ID"] = key
    env["QOD_S3_SECRET_ACCESS_KEY"] = secret
    if endpoint:
        env["QOD_S3_ENDPOINT"] = endpoint
    env["QOD_S3_REGION"] = region
    env["QOD_S3_URL_STYLE"] = url_style
    env["QOD_S3_USE_SSL"] = "true" if use_ssl else "false"
    return env


def bucket_env(base: dict, req: BucketRequest, run_id: str | None = None) -> dict:
    return _s3_env(
        base, f"{req.path}/qod-demo-{run_id or _run_id()}", False, req.access_key_id,
        req.secret_access_key, req.endpoint, req.region, req.url_style, req.use_ssl,
    )


def local_env(base: dict, store, run_id: str | None = None) -> dict:
    env = _s3_env(
        base, f"s3://{LOCAL_BUCKET}/qod-demo-{run_id or _run_id()}", True, store.access_key_id,
        store.secret_access_key, f"127.0.0.1:{store.s3_port}", "us-east-1", "path", False,
    )
    # A corporate proxy must never see the store's requests.
    for var in ("NO_PROXY", "no_proxy"):
        env[var] = "127.0.0.1" + (f",{env[var]}" if env.get(var) else "")
    return env


def weed_command(weed: Path, folder: Path, ports: dict) -> list[str]:
    """`-ip` defaults to the LAN address. Without the two `=0` flags the store also takes the fixed
    ports 8181 and 9101, and without `-logdir` its logs land in the system temp folder."""
    return [
        str(weed),
        f"-logdir={folder / 'logs'}",
        "server",
        f"-dir={folder / 'data'}",
        "-ip=127.0.0.1",
        "-ip.bind=127.0.0.1",
        f"-master.port={ports['master']}",
        f"-master.port.grpc={ports['master.grpc']}",
        f"-volume.port={ports['volume']}",
        f"-volume.port.grpc={ports['volume.grpc']}",
        f"-filer.port={ports['filer']}",
        f"-filer.port.grpc={ports['filer.grpc']}",
        "-s3",
        f"-s3.port={ports['s3']}",
        f"-s3.port.grpc={ports['s3.grpc']}",
        f"-s3.config={folder / 's3.json'}",
        "-s3.port.iceberg=0",
        "-s3.port.lance=0",
        "-master.volumeSizeLimitMB=1024",
    ]


def _free_ports() -> dict:
    socks = [socket.socket() for _ in PORT_NAMES]
    try:
        for s in socks:
            s.bind(("127.0.0.1", 0))
        return {name: s.getsockname()[1] for name, s in zip(PORT_NAMES, socks)}
    finally:
        for s in socks:
            s.close()


def _s3_answers(port: int) -> bool:
    """403 is the answer an anonymous request gets from a store that is up."""
    try:
        r = httpx.get(f"http://127.0.0.1:{port}/", timeout=1, trust_env=False)
    except httpx.HTTPError:
        return False
    return r.status_code in (200, 403)


def _log_tail(folder: Path) -> str:
    try:
        lines = (folder / "logs" / "weed.out").read_text(errors="replace").splitlines()
    except OSError:
        return ""
    return "\n".join(lines[-20:])


def _write_private(path: Path, text: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as out:
        out.write(text)


def _kill_tree(pid: int) -> None:
    if sys.platform == "win32":
        subprocess.call(
            ["taskkill", "/T", "/F", "/PID", str(pid)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    else:
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass


class LocalStore:
    """A SeaweedFS for one demo run, on loopback, with random keys, in a folder of its own."""

    def __init__(self, proc, folder, record, s3_port, access_key_id, secret_access_key):
        self._proc = proc
        self._folder = folder
        self._record = record
        self.s3_port = s3_port
        self.access_key_id = access_key_id
        self.secret_access_key = secret_access_key

    def __repr__(self) -> str:
        return f"LocalStore(127.0.0.1:{self.s3_port})"

    @classmethod
    def start(
        cls,
        weed: Path,
        record: Path | None = None,
        popen=subprocess.Popen,
        answers=_s3_answers,
        monotonic=time.monotonic,
        sleep=time.sleep,
    ) -> "LocalStore":
        record = record if record is not None else store_record_path()
        folder = Path(tempfile.mkdtemp(prefix=FOLDER_PREFIX))
        (folder / "data").mkdir()
        (folder / "logs").mkdir()
        key, secret = secrets.token_hex(12), secrets.token_hex(24)
        _write_private(
            folder / "s3.json",
            json.dumps(
                {
                    "identities": [
                        {
                            "name": "demo",
                            "credentials": [{"accessKey": key, "secretKey": secret}],
                            "actions": ["Admin", "Read", "Write", "List", "Tagging"],
                        }
                    ]
                }
            ),
        )
        ports = _free_ports()
        env = {k: os.environ[k] for k in _STORE_ENV_KEYS if k in os.environ}
        # Its own session, so a Ctrl-C at the terminal reaches the CLI and not the store: the store
        # outlives the jar, which still needs it while it shuts down.
        isolate = (
            {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
            if sys.platform == "win32"
            else {"start_new_session": True}
        )
        with (folder / "logs" / "weed.out").open("wb") as log:
            proc = popen(
                weed_command(weed, folder, ports),
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                **isolate,
            )
        record.parent.mkdir(parents=True, exist_ok=True)
        _write_private(
            record, json.dumps({"pid": proc.pid, "dir": str(folder), "owner": os.getpid()})
        )
        store = cls(proc, folder, record, ports["s3"], key, secret)
        deadline = monotonic() + READY_TIMEOUT_S
        while True:
            if proc.poll() is not None:
                tail = _log_tail(folder)
                store.stop()
                raise DemoS3Error(f"the local S3 store exited at start:\n{tail}")
            if answers(ports["s3"]):
                return store
            if monotonic() > deadline:
                tail = _log_tail(folder)
                store.stop()
                raise DemoS3Error(
                    f"the local S3 store did not answer within {READY_TIMEOUT_S:.0f} s:\n{tail}"
                )
            sleep(0.1)

    def stop(self) -> None:
        """A hard kill: everything it holds is being thrown away, and it takes many seconds to
        honor a SIGTERM. Safe to call twice."""
        if self._proc.poll() is None:
            if sys.platform == "win32":
                _kill_tree(self._proc.pid)
            self._proc.kill()
        try:
            self._proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
        shutil.rmtree(self._folder, ignore_errors=True)
        self._record.unlink(missing_ok=True)


def store_record_path() -> Path:
    """Seam: tests point this away from the real cache."""
    return launcher.default_cache_dir() / "weed" / "store.json"


def _command_line(pid: int) -> str | None:
    """The process's command line; None when it is gone or cannot be read."""
    try:
        if sys.platform == "win32":
            query = f'(Get-CimInstance Win32_Process -Filter "ProcessId={pid}").CommandLine'
            cmd = ["powershell", "-NoProfile", "-Command", query]
        else:
            cmd = ["ps", "-o", "command=", "-p", str(pid)]
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return out or None


def sweep_orphan(record=None, command_line=None, kill=None, echo=None) -> None:
    """Remove a store whose `qod` was killed outright. While its owner lives the store is that CLI's
    to stop: `qod stop` for a manager on another port must not take a running demo's store. A pid
    is only signalled when its command line still names `weed` and the recorded folder, as a
    recycled pid is the premise here."""
    record = record if record is not None else store_record_path()
    command_line = command_line if command_line is not None else _command_line
    kill = kill if kill is not None else _kill_tree
    echo = echo if echo is not None else typer.echo
    try:
        state = json.loads(record.read_text())
        pid, folder, owner = int(state["pid"]), str(state["dir"]), int(state["owner"])
    except FileNotFoundError:
        return
    except (OSError, ValueError, KeyError, TypeError):
        record.unlink(missing_ok=True)
        return
    if command_line(owner) is not None:
        return
    line = command_line(pid)
    if line is not None and "weed" in line and folder in line:
        echo(f"stopping an orphaned demo S3 store (pid {pid})")
        kill(pid)
    if Path(folder).name.startswith(FOLDER_PREFIX):
        shutil.rmtree(folder, ignore_errors=True)
    record.unlink(missing_ok=True)
