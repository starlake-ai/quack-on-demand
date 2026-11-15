#!/usr/bin/env bash
# Runs sbt in the search worktree against the part 2 test Postgres (qod-p2-pg, 127.0.0.1:27433),
# never the owner's on 5432. Usage: sbt_isolated.sh <p2> <sbt arguments...>
set -euo pipefail
P2="$1"; shift
docker exec qod-p2-pg pg_isready -U postgres > /dev/null || { echo "qod-p2-pg is not up" >&2; exit 2; }
export SL_TEST_PG_HOST=127.0.0.1 SL_TEST_PG_PORT=27433 SL_TEST_PG_USER=postgres
SL_TEST_PG_PASSWORD="$(cat "$P2/secrets/pg.txt")"; export SL_TEST_PG_PASSWORD
cd "$(dirname "$0")/../../.."
exec sbt "$@"
