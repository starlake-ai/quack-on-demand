#!/usr/bin/env bash
# CI's reading of a ctest log (.github/workflows/qod-node.yml): a test that was never added (its tool missing at
# configure) or that skipped itself (exit 77) passes ctest, so the jobs name what must have run.
#
#   ctest_gate.sh <ctest log> <allowed skips, an extended regex over whole names> <test that must pass>...
#
# Fails when a named test did not pass, or when a test skipped whose name the regex does not match whole. An
# empty regex allows no skip. Prints the skipped tests either way.
set -euo pipefail
log="$1"
allowed="$2"
shift 2
status=0
for name in "$@"; do
  if ! grep -Eq "Test +#[0-9]+: ${name}[ .]+Passed" "$log"; then
    echo "::error::ctest: ${name} did not pass (never added, skipped or failed)"
    status=1
  fi
done
skipped="$(sed -nE 's/^[[:space:]]*[0-9]+ - ([A-Za-z0-9_]+) \((Skipped|Not Run|Disabled)\)$/\1/p' "$log")"
for name in $skipped; do
  if [[ -n "$allowed" ]] && [[ "$name" =~ ^(${allowed})$ ]]; then
    echo "skipped (allowed): $name"
  else
    echo "::error::ctest: $name skipped"
    status=1
  fi
done
exit "$status"
