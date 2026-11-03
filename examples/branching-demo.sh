#!/usr/bin/env bash
# Two-minute branching demo (Epic 1): an agent proposes a change on a branch, a human
# reviews the diff and merges. Needs a running manager with the demo tenants loaded
# (`qod demo` or LOAD_TPC=1 ./scripts/run-jar.sh) and two tenant admins of `acme` (the demo has
# `alice` and `acme-admin`), one CLI profile each. A token acts as its owner and a merge refuses its
# own proposer (403 self_merge_forbidden), so the agent's token comes from the other admin:
#   - the default profile logged in as one tenant admin of `acme` (the reviewer)
#   - AGENT_PAT: a branch-only personal access token for the agent (the agent's
#       commands below run with QOD_TOKEN set to it), minted from the other admin's profile with
#       qod --profile <other-admin> auth pat create --name agent --branch-only --database acme_tpch1
# Every step is a plain `qod` call so the same flow works from Claude Code, Codex or a shell.
set -euo pipefail

TENANT="${TENANT:-acme}"
DB="${DB:-acme_tpch1}"
POOL="${POOL:-bi}"
BRANCH="${BRANCH:-fix-nation-comments}"
AGENT_PAT="${AGENT_PAT:?set AGENT_PAT to a branch-only PAT (qod auth pat create --branch-only)}"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

step "1. The agent tries to write main directly: refused (branch-only token)"
QOD_TOKEN="$AGENT_PAT" qod sql --tenant "$TENANT" --pool "$POOL" \
  "UPDATE tpch1.nation SET n_comment = 'edited by agent' WHERE n_nationkey = 3" || true

step "2. The agent creates a branch (zero-copy, seconds)"
QOD_TOKEN="$AGENT_PAT" qod branch create --tenant "$TENANT" --db "$DB" --name "$BRANCH" --ttl-hours 24

step "3. The agent writes on the branch"
QOD_TOKEN="$AGENT_PAT" qod sql --tenant "$TENANT" --pool "$POOL" --branch "$BRANCH" \
  "UPDATE tpch1.nation SET n_comment = 'edited by agent' WHERE n_nationkey = 3"
QOD_TOKEN="$AGENT_PAT" qod sql --tenant "$TENANT" --pool "$POOL" --branch "$BRANCH" \
  "INSERT INTO tpch1.nation VALUES (25, 'ATLANTIS', 3, 'proposed by agent')"

step "4. Main is untouched"
qod sql --tenant "$TENANT" --pool "$POOL" \
  "SELECT n_nationkey, n_name, n_comment FROM tpch1.nation WHERE n_nationkey IN (3, 25) ORDER BY 1"

step "5. The agent proposes the merge"
QOD_TOKEN="$AGENT_PAT" qod branch propose --tenant "$TENANT" --db "$DB" --branch "$BRANCH"

step "6. The reviewer inspects the change set and the row-level diff"
qod branch changes --tenant "$TENANT" --db "$DB" --branch "$BRANCH"
qod branch diff --tenant "$TENANT" --db "$DB" --branch "$BRANCH" --schema tpch1 --table nation

step "7. The reviewer reads the open proposal, then merges it by its id (one stamped snapshot, tagged)"
qod branch show --tenant "$TENANT" --db "$DB" --branch "$BRANCH"
PROPOSAL="$(qod --json branch show --tenant "$TENANT" --db "$DB" --branch "$BRANCH" \
  | python3 -c 'import json, sys; print(next(m["id"] for m in json.load(sys.stdin)["merges"] if m["status"] == "proposed"))')"
qod branch merge --tenant "$TENANT" --db "$DB" --branch "$BRANCH" --proposal "$PROPOSAL"

step "8. Main now carries the change; the branch is gone"
qod sql --tenant "$TENANT" --pool "$POOL" \
  "SELECT n_nationkey, n_name, n_comment FROM tpch1.nation WHERE n_nationkey IN (3, 25) ORDER BY 1"
qod catalog tags "$TENANT" "$DB"
qod branch list --tenant "$TENANT" --db "$DB" --all
