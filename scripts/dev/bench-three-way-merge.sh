#!/usr/bin/env bash
# The three-way merge at scale, outside `sbt test`. Usage:
#   scripts/dev/bench-three-way-merge.sh <rows> <changed rows per side>
# See ThreeWayMergeScaleBench for the environment it reads. Needs the local test Postgres, and about
# 32 bytes per row of disk, plus a spill of about 300GB at 1B rows with QOD_BENCH_VERIFY=1.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
export QOD_BENCH_ROWS="${1:?usage: $0 <rows> <changed rows per side>}"
export QOD_BENCH_CHANGES="${2:?usage: $0 <rows> <changed rows per side>}"
log="${TMPDIR:-/tmp}/bench-three-way-${QOD_BENCH_ROWS}-${QOD_BENCH_CHANGES}-$$.log"
echo "log: $log"
env -u AI_AGENT SBT_OPTS=-Xmx4G sbt "testOnly ai.starlake.quack.ondemand.branch.ThreeWayMergeScaleBench" 2>&1 | tee "$log"
