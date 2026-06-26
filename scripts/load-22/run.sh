#!/usr/bin/env bash
# run.sh - run the TPC-H 22-query benchmark (scripts/load-22/benchmark.py)
# against the quack-on-demand FlightSQL edge, producing a CSV + HTML report
# (X axis = query number, Y axis = median duration in ms).
#
# It provisions a self-managed Python venv with the ADBC FlightSQL driver
# (the system python's pip is often unusable here), created on first run
# under ${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv} and reused afterwards.
# Behind a proxy, set PIP_PROXY (e.g. PIP_PROXY=http://127.0.0.1:3128) so the
# one-time install can reach PyPI.
#
# All flags are passed through to benchmark.py. Defaults target the demo
# acme/bi/tpch1 workload seeded by scripts/load-tpch-dbgen.sh:
#
#   scripts/load-22/run.sh
#   scripts/load-22/run.sh --runs 10 --warmup 2
#   scripts/load-22/run.sh --tenant globex --pool bi --schema tpch1
#   scripts/load-22/run.sh --out-dir /tmp/tpch-bench
#
# Output: <out-dir>/tpch-bench.csv and <out-dir>/tpch-bench.html
# (out-dir defaults to scripts/load-22/out).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VENV="${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}"
PY="$VENV/bin/python"
if [[ ! -x "$PY" ]] || ! "$PY" -c "import adbc_driver_flightsql.dbapi, pyarrow" 2>/dev/null; then
  echo "run.sh: provisioning ADBC driver venv at $VENV (one-time) ..." >&2
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --upgrade pip ${PIP_PROXY:+--proxy "$PIP_PROXY"} \
    adbc_driver_flightsql adbc_driver_manager pyarrow >&2
fi

exec "$PY" "$HERE/benchmark.py" "$@"

