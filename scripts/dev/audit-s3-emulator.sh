#!/usr/bin/env bash
# The AWS Emulator under podman, for S3AuditSinkSpec (QOD_AUDIT_SINK=s3 against a real S3 API).
#
#   scripts/dev/audit-s3-emulator.sh start   build the image once (scripts/dev/aws-emulator.Containerfile), run it
#   eval "$(scripts/dev/audit-s3-emulator.sh env)"   export QOD_TEST_S3_* from the emulator's home
#   sbt "testOnly *S3AuditSinkSpec"
#   scripts/dev/audit-s3-emulator.sh stop    stop and remove the container (its state is in memory)
#
# Before trusting a green Object Lock case on it, qualify the emulator with the S3 checks of
# https://github.com/aws/aws-emulator-evaluation-suite (README there):
#   checksum-sha256, bucket-default-retention-derives-the-retain-until-date-and-blocks-the-delete,
#   delete-object-under-retention-rejected
# run as: uv run fidelity-tests-runner run behavioral/s3/check_<name>.py \
#           --endpoint https://127.0.0.1:9297 --emulator-home "$HOME"
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
name=qod-audit-s3
image=qod-aws-emulator
port=9297
home_dir="$HOME/.aws/emulator"

case "${1:-}" in
  start)
    # Public images only: an empty auth file keeps podman off any credential helper a Docker
    # config names, which can fail a pull that needs no credentials. Telemetry to AWS is off. On
    # Linux rootless podman, the container's uid 1000 may need --userns=keep-id to write the mount.
    auth="$(mktemp)"
    trap 'rm -f "$auth"' EXIT
    echo '{}' >"$auth"
    if ! podman image exists "$image"; then
      case "$(uname -m)" in arm64 | aarch64) arch=aarch64 ;; *) arch=x86_64 ;; esac
      podman build --authfile "$auth" --build-arg RPM_ARCH="$arch" -t "$image" \
        -f "$here/aws-emulator.Containerfile" "$here"
    fi
    mkdir -p "$home_dir"
    podman run --authfile "$auth" --rm -d --name "$name" --memory=2g -p "127.0.0.1:$port:$port" \
      -e HOME="$HOME" -e EMULATOR_TELEMETRY=off -v "$home_dir:$home_dir" "localhost/$image" -p "$port" \
      >/dev/null
    for _ in $(seq 1 60); do
      # The port opens before the emulator writes its keys: wait for both.
      if [[ -s "$home_dir/$port/emulator_credentials" ]] && (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
        exit 0
      fi
      sleep 1
    done
    echo "the emulator did not open port $port; see: podman logs $name" >&2
    exit 1
    ;;
  env)
    dir="$home_dir/$port"
    creds="$dir/emulator_credentials"
    [[ -f "$creds" ]] || { echo "no $creds: is the emulator running?" >&2; exit 1; }
    key() { sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "${2:-$creds}" | head -1; }
    out() { printf 'export %s=%q\n' "$1" "$2"; }
    out QOD_TEST_S3_ENDPOINT "https://127.0.0.1:$port"
    out QOD_TEST_S3_REGION "$(key region "$dir/config")"
    out QOD_TEST_S3_ACCESS_KEY_ID "$(key aws_access_key_id)"
    out QOD_TEST_S3_SECRET_ACCESS_KEY "$(key aws_secret_access_key)"
    if [[ -f "$dir/ca.pem" ]]; then out QOD_TEST_S3_CA_FILE "$dir/ca.pem"; fi
    ;;
  stop)
    podman stop "$name" >/dev/null 2>&1 || true
    ;;
  *)
    echo "usage: $0 start|env|stop" >&2
    exit 2
    ;;
esac
