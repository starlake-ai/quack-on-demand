#!/usr/bin/env bash
# adbc.sh - run one SQL query against a FlightSQL endpoint (the quack-on-demand
# edge, or any Arrow FlightSQL server) and print the result as a table.
#
# Uses the ADBC FlightSQL driver from a self-managed Python venv (the system
# python's pip is often unusable here), created on first run under
#   ${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}
# and reused afterwards. Behind a proxy, set PIP_PROXY (e.g.
# PIP_PROXY=http://127.0.0.1:3128) so the one-time install can reach PyPI.
#
# Usage:
#   scripts/adbc.sh --url grpc+tls://localhost:31338 \
#       --user alice --password demo-alice \
#       --tenant acme --pool bi --insecure \
#       --query "SELECT count(*) FROM tpch1.customer"
#
# Flags:
#   --url        FlightSQL URI (grpc+tls://host:port or grpc://host:port). Required.
#   --user       Username (Basic auth).            [env LT_USER]
#   --password   Password.                         [env LT_PASSWORD]
#   --query      SQL to run (or pass on stdin).    [env LT_QUERY]
#   --tenant     gRPC `tenant` routing header.     [env LT_TENANT]
#   --pool       gRPC `pool` routing header.       [env LT_POOL]
#   --superuser  Add the `superuser=true` header (system-realm login).
#   --insecure   Skip TLS certificate verification (self-signed dev certs).
set -euo pipefail

URL="" USER="${LT_USER:-}" PASSWORD="${LT_PASSWORD:-}" QUERY="${LT_QUERY:-}"
TENANT="${LT_TENANT:-}" POOL="${LT_POOL:-}" SUPERUSER="" INSECURE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)       URL="$2"; shift 2 ;;
    --user)      USER="$2"; shift 2 ;;
    --password)  PASSWORD="$2"; shift 2 ;;
    --query)     QUERY="$2"; shift 2 ;;
    --tenant)    TENANT="$2"; shift 2 ;;
    --pool)      POOL="$2"; shift 2 ;;
    --superuser) SUPERUSER="1"; shift ;;
    --insecure)  INSECURE="1"; shift ;;
    -h|--help)   sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -z "$QUERY" && ! -t 0 ]] && QUERY="$(cat)"
[[ -z "$URL"   ]] && { echo "error: --url is required" >&2; exit 2; }
[[ -z "$QUERY" ]] && { echo "error: --query is required (or pipe SQL on stdin)" >&2; exit 2; }

VENV="${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}"
PY="$VENV/bin/python"
if [[ ! -x "$PY" ]] || ! "$PY" -c "import adbc_driver_flightsql.dbapi, pyarrow" 2>/dev/null; then
  echo "adbc.sh: provisioning ADBC driver venv at $VENV (one-time) ..." >&2
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --upgrade pip ${PIP_PROXY:+--proxy "$PIP_PROXY"} \
    adbc_driver_flightsql adbc_driver_manager pyarrow >&2
fi

ADBC_URL="$URL" ADBC_USER="$USER" ADBC_PASSWORD="$PASSWORD" ADBC_QUERY="$QUERY" \
ADBC_TENANT="$TENANT" ADBC_POOL="$POOL" ADBC_SUPERUSER="$SUPERUSER" ADBC_INSECURE="$INSECURE" \
"$PY" - <<'PYEOF'
import os, sys
import adbc_driver_flightsql.dbapi as fs
from adbc_driver_flightsql import DatabaseOptions as D

url = os.environ["ADBC_URL"]
H = D.RPC_CALL_HEADER_PREFIX.value
kw = {}
if os.environ.get("ADBC_USER"):     kw["username"] = os.environ["ADBC_USER"]
if os.environ.get("ADBC_PASSWORD"): kw["password"] = os.environ["ADBC_PASSWORD"]
if os.environ.get("ADBC_INSECURE") and url.startswith("grpc+tls"):
    kw[D.TLS_SKIP_VERIFY.value] = "true"
if os.environ.get("ADBC_TENANT"):   kw[H + "tenant"] = os.environ["ADBC_TENANT"]
if os.environ.get("ADBC_POOL"):     kw[H + "pool"]   = os.environ["ADBC_POOL"]
if os.environ.get("ADBC_SUPERUSER"):kw[H + "superuser"] = "true"

def render(cols, rows):
    cells = [[("NULL" if v is None else str(v)) for v in r] for r in rows]
    widths = [len(c) for c in cols]
    for r in cells:
        for i, v in enumerate(r):
            widths[i] = max(widths[i], len(v))
    bar = "+".join("-" * (w + 2) for w in widths)
    def line(vals): return "| " + " | ".join(v.ljust(widths[i]) for i, v in enumerate(vals)) + " |"
    print("+" + bar + "+")
    print(line(cols))
    print("+" + bar + "+")
    for r in cells:
        print(line(r))
    print("+" + bar + "+")
    print(f"({len(rows)} row{'' if len(rows)==1 else 's'})")

try:
    conn = fs.connect(uri=url, db_kwargs=kw)
except Exception as e:
    print(f"connect failed: {e}", file=sys.stderr); sys.exit(1)
try:
    with conn.cursor() as cur:
        cur.execute(os.environ["ADBC_QUERY"])
        rows = cur.fetchall()
        cols = [d[0] for d in cur.description] if cur.description else []
    render(cols, rows)
finally:
    conn.close()
PYEOF
