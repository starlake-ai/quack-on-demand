#!/usr/bin/env bash
# Runs qod-node on spike/smoke.sql in a scratch directory and checks what the spike needs to know on
# this platform: the binary links and starts against the release libduckdb, the five extensions load,
# post_bind reports the DuckLake table read through the secure view, and every core setting's scope
# is recoverable at runtime. Every check runs and reports; the exit status is non-zero if any failed.
#
#   check-smoke.sh <qod-node binary> <scratch dir>
set -uo pipefail

node_bin="${1:?usage: check-smoke.sh <qod-node binary> <scratch dir>}"
work="${2:?usage: check-smoke.sh <qod-node binary> <scratch dir>}"
smoke_sql="$(cd "$(dirname "$0")" && pwd)/smoke.sql"
failed=0

rm -rf "$work"
mkdir -p "$work"
cd "$work" || exit 1

if ! "$node_bin" --init "$smoke_sql" --list-settings > settings.raw 2> smoke.raw; then
  echo "::error::qod-node exited with an error"
  failed=1
fi
tr -d '\r' < settings.raw > settings.tsv
tr -d '\r' < smoke.raw > smoke.log

echo "== init"
grep '^\[init\]' smoke.log
if grep -q '^\[init\] .* failed' smoke.log; then
  echo "::error::an init statement failed (see above)"
  failed=1
fi

echo "== secure-view read"
if ! grep -E '^\[post_bind\].*reads=\[lake\.main\.orders\([^]]*\)\].*secure_views=\[memory\.main\.big_orders\]' smoke.log; then
  echo "::error::post_bind did not report lake.main.orders read through memory.main.big_orders"
  grep '^\[post_bind\]' smoke.log | tail -5
  failed=1
fi

echo "== settings"
echo "core: $(grep -c '^core' settings.tsv), extension: $(grep -c '^extension' settings.tsv)"
cut -f1,3 settings.tsv | sort | uniq -c
# A core setting without a scope target must have exactly one setter, or its scope is not recoverable.
if ! awk -F'\t' '$1 == "core" && $3 == "invalid" && $4 != "g-" && $4 != "-l" { print "::error::no recoverable scope: " $2; bad = 1 } END { exit bad }' settings.tsv; then
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  echo "smoke passed"
fi
exit "$failed"
