#!/usr/bin/env bash
# Runs qod sql as the part 2 stack's superuser (admin, superuser=true) on tenant acme, pool bi.
# Usage: qod_sql.sh <p2> [qod sql arguments...], e.g. qod_sql.sh <p2> -f file.sql
# The password goes to qod sql through QOD_PASSWORD (environment, never argv), read from
# <p2>/secrets/admin.txt; never printed. Edge pinned to 127.0.0.1:27338 by qod_env.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../../.." && pwd)"
. "$HERE/qod_env.sh" "$1"; shift
QOD_USER=admin QOD_SUPERUSER=true QOD_PASSWORD="$(cat "$P2/secrets/admin.txt")" \
  exec "$WT/cli/.venv/bin/qod" sql --tenant acme --pool bi --superuser "$@"
