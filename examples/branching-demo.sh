#!/usr/bin/env bash
# Two-minute branching demo: an agent, a plain user of `acme`, proposes a change on a branch; a tenant
# admin reviews the diff and merges it.
#
# Needs a running manager with the full demo loaded (`LOAD_TPC=1 ./scripts/run-jar.sh`, or
# `LOAD_TPCH=1 qod start`), and two CLI profiles:
#   - `agent`, the plain user: the demo's `bob`, who may connect to pool `etl` and write tpch1.lineitem:
#       printf demo-bob | qod --profile agent login --tenant acme --username bob --pool etl --password-stdin
#   - the default profile, the reviewer: `acme-admin`, a tenant admin of `acme`:
#       printf demo-acme-admin | qod login --tenant acme --username acme-admin --pool etl --password-stdin
# DEMO=minimal has no plain user: it needs the full demo.
#
# `qod sql` logs in with the profile's user and asks for its password; AGENT_PASSWORD and
# REVIEWER_PASSWORD spare the prompts.
#
# Every step is a plain `qod` call, so the same flow works from Claude Code, Codex or a shell. Over
# MCP an agent uses its own token instead: `qod auth pat create --name agent --branch-only` is what
# makes "agents never write main" a hard rule, since that token's writes on the live database are
# refused. `qod sql` carries no token, so this script cannot show it.
set -euo pipefail

TENANT="${TENANT:-acme}"
DB="${DB:-acme_tpch}"
POOL="${POOL:-etl}"
BRANCH="${BRANCH:-fix-lineitem-comments}"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

agent() { qod --profile agent "$@"; }
agent_sql() { QOD_PASSWORD="${AGENT_PASSWORD:-}" agent sql --tenant "$TENANT" --pool "$POOL" "$@"; }
reviewer_sql() { QOD_PASSWORD="${REVIEWER_PASSWORD:-}" qod sql --tenant "$TENANT" --pool "$POOL" "$@"; }

READ_BACK="SELECT l_orderkey, l_linenumber, l_comment FROM tpch1.lineitem WHERE l_comment LIKE '%by agent' ORDER BY 1, 2"

step "1. The agent creates a branch (zero-copy, seconds). A plain user asks for a TTL: at most the server's default"
agent branch create --tenant "$TENANT" --db "$DB" --name "$BRANCH" --ttl-hours 24

step "2. The agent writes on the branch"
agent_sql --branch "$BRANCH" \
  "UPDATE tpch1.lineitem SET l_comment = 'edited by agent' WHERE l_orderkey = 3 AND l_linenumber = 1"
agent_sql --branch "$BRANCH" \
  "INSERT INTO tpch1.lineitem SELECT * REPLACE (l_linenumber + 100 AS l_linenumber, 'added by agent' AS l_comment) FROM tpch1.lineitem WHERE l_orderkey = 3 AND l_linenumber = 1"

step "3. Main is untouched"
reviewer_sql "$READ_BACK"

step "4. The agent reads its change set and proposes the merge. It sees the tables it holds a grant on; otherTables counts the rest"
agent branch changes --tenant "$TENANT" --db "$DB" --branch "$BRANCH"
PROPOSED="$(agent --json branch propose --tenant "$TENANT" --db "$DB" --branch "$BRANCH")"
printf '%s\n' "$PROPOSED"
PROPOSAL="$(printf '%s' "$PROPOSED" | python3 -c 'import json, sys; print(json.load(sys.stdin)["merge"]["id"])')"

step "5. The agent may not merge its own proposal, nor anyone's: merge needs an admin (403 admin_required)"
if agent branch merge --tenant "$TENANT" --db "$DB" --branch "$BRANCH" --proposal "$PROPOSAL"; then
  echo "the plain user merged: this should have been refused" >&2
  exit 1
fi

step "6. The reviewer inspects the change set and the row-level diff"
qod branch changes --tenant "$TENANT" --db "$DB" --branch "$BRANCH"
qod branch diff --tenant "$TENANT" --db "$DB" --branch "$BRANCH" --schema tpch1 --table lineitem

step "7. The reviewer reads the open proposal, then merges it by its id (one stamped snapshot, tagged)"
qod branch show --tenant "$TENANT" --db "$DB" --branch "$BRANCH"
qod branch merge --tenant "$TENANT" --db "$DB" --branch "$BRANCH" --proposal "$PROPOSAL"

step "8. Main now carries the change; the branch is gone"
reviewer_sql "$READ_BACK"
qod catalog tags "$TENANT" "$DB"
qod branch list --tenant "$TENANT" --db "$DB" --all
