#!/usr/bin/env python3
"""Starts the loopback SeaweedFS store of QoD Search Phase 0 part 2, Task 15a.

Usage: uv run --no-project --with boto3 python -I weed_up.py <p2>

1. Gets the pinned SeaweedFS binary through the worktree CLI's own sha256-checked download
   (`qod_cli.launcher.ensure_weed()`, run by <wt>/cli/.venv/bin/python). Never downloads by hand.
2. Makes <p2>/weed/{data,logs} (mode 700) and writes a random access key and secret into
   <p2>/secrets/weed.json and into the store's <p2>/weed/s3.json (identities format, actions
   Admin, Read, Write, List), both mode 600. Neither value is ever printed.
3. Starts the binary with the flags of `weed_command` (cli/src/qod_cli/demo_s3.py) on the part 2
   ports 27460 to 27468, all bound to 127.0.0.1, in its own session, stdout and stderr to
   <p2>/weed/logs/weed.out, with an environment of PATH and HOME only.
4. Waits until http://127.0.0.1:27460/ answers 403 (up, anonymous), then creates the bucket
   qod-p2-search with a signed CreateBucket (boto3, path style).
5. Writes the pid to <p2>/weed/weed.pid.

Prints `store up, bucket qod-p2-search` on success. Refuses to start when <p2>/weed exists.
"""
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
BUCKET = "qod-p2-search"
PORTS = {
    "s3": 27460, "s3.grpc": 27461, "master": 27462, "master.grpc": 27463,
    "volume": 27464, "volume.grpc": 27465, "filer": 27466, "filer.grpc": 27467,
    "metrics": 27468,
}


def write_private(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(fd, data.encode())
    finally:
        os.close(fd)


def main():
    p2 = sys.argv[1]
    base = os.path.join(p2, "weed")
    if os.path.exists(base):
        print(f"{base} exists: run weed_down.sh first", flush=True)
        return 1
    weed = subprocess.run(
        [os.path.join(WT, "cli", ".venv", "bin", "python"), "-I", "-c",
         "from qod_cli.launcher import ensure_weed; print(ensure_weed())"],
        check=True, capture_output=True, text=True).stdout.strip().splitlines()[-1]
    version = subprocess.run([weed, "version"], capture_output=True, text=True).stdout.strip()
    print(f"weed: {weed} ({version})", flush=True)

    os.makedirs(base, mode=0o700)
    os.makedirs(os.path.join(base, "data"), mode=0o700)
    os.makedirs(os.path.join(base, "logs"), mode=0o700)
    key, secret = secrets.token_hex(12), secrets.token_hex(24)
    write_private(os.path.join(p2, "secrets", "weed.json"),
                  json.dumps({"key": key, "secret": secret}))
    s3_config = os.path.join(base, "s3.json")
    write_private(s3_config, json.dumps({"identities": [{
        "name": "qod-p2", "credentials": [{"accessKey": key, "secretKey": secret}],
        "actions": ["Admin", "Read", "Write", "List"]}]}))

    p = PORTS
    cmd = [
        weed, f"-logdir={base}/logs", "server", f"-dir={base}/data",
        "-ip=127.0.0.1", "-ip.bind=127.0.0.1",
        f"-master.port={p['master']}", f"-master.port.grpc={p['master.grpc']}",
        f"-volume.port={p['volume']}", f"-volume.port.grpc={p['volume.grpc']}",
        f"-filer.port={p['filer']}", f"-filer.port.grpc={p['filer.grpc']}",
        "-s3", f"-s3.port={p['s3']}", f"-s3.port.grpc={p['s3.grpc']}",
        f"-s3.config={s3_config}", "-s3.port.iceberg=0", "-s3.port.lance=0",
        "-master.volumeSizeLimitMB=1024",
        "-metricsIp=127.0.0.1", f"-metricsPort={p['metrics']}",
    ]
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": base}
    with open(os.path.join(base, "logs", "weed.out"), "wb") as out:
        proc = subprocess.Popen(cmd, env=env, cwd=base, stdin=subprocess.DEVNULL, stdout=out,
                                stderr=subprocess.STDOUT, start_new_session=True)
    with open(os.path.join(base, "weed.pid"), "w", encoding="utf-8") as f:
        f.write(str(proc.pid))
    print("flags: " + " ".join(a.replace(p2, "<p2>") for a in cmd[1:]), flush=True)

    up = False
    for _ in range(240):
        if proc.poll() is not None:
            print(f"weed exited with {proc.returncode}; see <p2>/weed/logs/weed.out", flush=True)
            return 1
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{p['s3']}/", timeout=1)
        except urllib.error.HTTPError as e:
            if e.code == 403:
                up = True
                break
        except Exception:  # noqa: BLE001 - not listening yet
            pass
        time.sleep(0.5)
    if not up:
        print("store did not answer 403 within 120 s", flush=True)
        return 1

    import boto3  # noqa: PLC0415 - only once the store is up
    from botocore.config import Config  # noqa: PLC0415

    s3 = boto3.client("s3", endpoint_url=f"http://127.0.0.1:{p['s3']}", aws_access_key_id=key,
                      aws_secret_access_key=secret, region_name="us-east-1",
                      config=Config(s3={"addressing_style": "path"}))
    del secret
    for attempt in range(30):
        try:
            s3.create_bucket(Bucket=BUCKET)
            break
        except Exception as exc:  # noqa: BLE001 - the filer can lag the S3 port
            if attempt == 29:
                print(f"CreateBucket failed: {type(exc).__name__}", flush=True)
                return 1
            time.sleep(1)
    names = [b["Name"] for b in s3.list_buckets().get("Buckets", [])]
    if BUCKET not in names:
        print("bucket not listed after CreateBucket", flush=True)
        return 1
    print(f"store up, bucket {BUCKET}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
