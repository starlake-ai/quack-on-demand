#!/usr/bin/env bash
# Process-level tests for qod-node (POSIX). Run by ctest, or directly:
#
#   process_test.sh <qod-node binary> [<handle_tool binary>]
#
# With DUCKDB_CLI set to a release duckdb CLI, it also talks to the node over quack: a statement
# without a session handle is refused by the parse screen, before binding, and with handle_tool a
# valid prelude installs, a replayed or foreign-node one does not. A check it cannot run prints a
# SKIP line saying which and why.
set -uo pipefail

node_bin="${1:?usage: process_test.sh <qod-node binary> [<handle_tool binary>]}"
handle_tool="${2:-}"
work="$(mktemp -d)"
failures=0
pids=()
# The node key every test node gets, and its id.
node_key="4242424242424242424242424242424242424242424242424242424242424242"
node_id="quack-test-node-1"

cleanup() {
  for pid in "${pids[@]+"${pids[@]}"}"; do
    kill "$pid" 2>/dev/null || true
  done
  rm -rf "$work"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*"
  failures=$((failures + 1))
}

# Ends a background job of this shell and reaps it here, so bash's "Terminated" job notice (printed
# whenever a later wait finds a job killed by a signal) does not litter the test's output.
kill_quietly() {
  kill "$1" 2>/dev/null
  wait "$1" 2>/dev/null
}

# Waits up to 30 s for pattern in file. Returns non-zero on timeout.
wait_for() {
  local pattern="$1" file="$2"
  for _ in $(seq 1 150); do
    if grep -q "$pattern" "$file" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

# Waits up to 10 s for pid (a child of this shell) to exit and sets exit_status. Returns non-zero on
# timeout. Not for $(...): a subshell cannot wait on this shell's children.
exit_status=""
wait_exit() {
  local pid="$1"
  for _ in $(seq 1 50); do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid"
      exit_status=$?
      return 0
    fi
    sleep 0.2
  done
  return 1
}

port() {
  echo $((30000 + RANDOM % 20000))
}

# A blank and non-ASCII letters in the name: the path reaches qod-node as an argument.
new_secrets() {
  local dir="$work/secrets é ü.$RANDOM"
  mkdir -p "$dir"
  printf '%s' "$1" > "$dir/QOD_NODE_TOKEN"
  printf '%s' "${2-$node_key}" > "$dir/QOD_NODE_AUTHZ_KEY"
  echo "$dir"
}

# --- build marker ---------------------------------------------------------------------------------
marker="$("$node_bin" --build-info)"
if [[ ! "$marker" =~ ^qod-node-build-info:protocol=2\;duckdb=v[0-9]+\.[0-9]+\.[0-9]+\;duckdb_source=[0-9a-f]+$ ]]; then
  fail "unexpected build marker: $marker"
fi

# --- usage errors ---------------------------------------------------------------------------------
"$node_bin" > "$work/usage.out" 2>&1
status=$?
[[ $status -eq 2 ]] || fail "no arguments: exit $status, expected 2"
grep -q -- '--port is required' "$work/usage.out" || fail "no arguments: missing '--port is required'"

"$node_bin" --port 70000 > /dev/null 2>&1
[[ $? -eq 2 ]] || fail "--port 70000 must be refused"

"$node_bin" --port "$(port)" --secrets-dir "$work/x" --secret-directory "$work/secret-dir" > "$work/no-id.out" 2>&1
status=$?
[[ $status -eq 2 ]] || fail "no --node-id: exit $status, expected 2"
grep -q -- '--node-id is required' "$work/no-id.out" || fail "no --node-id: missing '--node-id is required'"

# --- missing or malformed secrets -----------------------------------------------------------------
mkdir -p "$work/empty-secrets"
"$node_bin" --port "$(port)" --secrets-dir "$work/empty-secrets" --secret-directory "$work/secret-dir" \
  --node-id "$node_id" > "$work/no-token.out" 2>&1
status=$?
[[ $status -eq 1 ]] || fail "missing token: exit $status, expected 1"
grep -q 'has no QOD_NODE_TOKEN' "$work/no-token.out" || fail "missing token: message not found"

mkdir -p "$work/no-key"
printf 'tok-no-key' > "$work/no-key/QOD_NODE_TOKEN"
"$node_bin" --port "$(port)" --secrets-dir "$work/no-key" --secret-directory "$work/secret-dir" \
  --node-id "$node_id" > "$work/no-key.out" 2>&1
status=$?
[[ $status -eq 1 ]] || fail "missing node key: exit $status, expected 1"
grep -q 'has no QOD_NODE_AUTHZ_KEY' "$work/no-key.out" || fail "missing node key: message not found"

# A node key that is not exactly 64 lowercase hex digits is refused naming the key, never its value
# (nor any part of it: the first 8 characters are looked for).
refuses_key() {
  local label="$1" value="$2" message="$3" status
  "$node_bin" --port "$(port)" --secrets-dir "$(new_secrets tok-bad-key "$value")" \
    --secret-directory "$work/secret-dir" --node-id "$node_id" > "$work/bad-key.out" 2>&1
  status=$?
  [[ $status -eq 1 ]] || fail "$label node key: exit $status, expected 1"
  grep -q "$message" "$work/bad-key.out" || fail "$label node key: '$message' not found"
  if [[ -n "$value" ]] && grep -q -F -- "${value:0:8}" "$work/bad-key.out"; then
    fail "$label node key: the key reached the output"
  fi
}
hex16="abcdef0123456789"
lower_key="$hex16$hex16$hex16$hex16"
not_hex="QOD_NODE_AUTHZ_KEY is not 64 lowercase hex digits"
refuses_key "non-hex" "4242ZZ" "$not_hex"
refuses_key "uppercase" "$(printf '%s' "$lower_key" | tr 'a-f' 'A-F')" "$not_hex"
refuses_key "63-digit" "${lower_key%?}" "$not_hex"
refuses_key "65-digit" "${lower_key}0" "$not_hex"
# An empty file never reaches the format check: the secret reader refuses it first.
refuses_key "empty" "" "QOD_NODE_AUTHZ_KEY is empty"

# An ephemeral secrets directory goes even when a read fails.
mkdir -p "$work/empty-ephemeral"
"$node_bin" --port "$(port)" --secrets-dir "$work/empty-ephemeral" --secrets-ephemeral \
  --secret-directory "$work/secret-dir" --node-id "$node_id" > /dev/null 2>&1
[[ ! -e "$work/empty-ephemeral" ]] || fail "missing token: the ephemeral secrets directory was kept"

# --- a stop while starting ends the node at once --------------------------------------------------
# An init FIFO no one writes blocks startup in open(), where only an immediate exit reaches it.
mkfifo "$work/stuck-init"
"$node_bin" --port "$(port)" --bind 127.0.0.1 --secrets-dir "$(new_secrets token-stuck)" \
  --secret-directory "$work/secret-dir" --node-id "$node_id" --init "$work/stuck-init" \
  > "$work/stuck-term.out" 2>&1 &
stuck=$!
pids+=("$stuck")
sleep 1
kill -TERM "$stuck"
if wait_exit "$stuck"; then
  [[ "$exit_status" -eq 143 ]] || fail "SIGTERM while starting: exit $exit_status, expected 143"
  grep -q 'stopping before ready: signal 15' "$work/stuck-term.out" ||
    fail "SIGTERM while starting: no 'stopping before ready: signal 15'"
else
  fail "SIGTERM while starting: the node did not stop"
fi

sleep 300 &
stuck_parent=$!
pids+=("$stuck_parent")
"$node_bin" --port "$(port)" --bind 127.0.0.1 --secrets-dir "$(new_secrets token-orphan)" \
  --secret-directory "$work/secret-dir" --node-id "$node_id" --init "$work/stuck-init" \
  --parent-pid "$stuck_parent" > "$work/stuck-parent.out" 2>&1 &
orphan=$!
pids+=("$orphan")
sleep 1
kill_quietly "$stuck_parent"
if wait_exit "$orphan"; then
  [[ "$exit_status" -eq 1 ]] || fail "parent end while starting: exit $exit_status, expected 1"
  grep -q 'stopping before ready: parent process' "$work/stuck-parent.out" ||
    fail "parent end while starting: no 'stopping before ready: parent process'"
else
  fail "parent end while starting: the node outlived its parent"
fi

# --- serve: best-effort init, secrets kept out of the output, identity checks, parent watch -------
token="token-$RANDOM$RANDOM"
secrets="$(new_secrets "$token")"
# The last three statements try to replace the parse screen with another authorization function
# (INSTALL and LOAD first: the setting belongs to quack). The refusal check below only passes because
# the node installs its own screen after the init SQL.
cat > "$work/init.sql" <<'SQL'
CREATE TABLE answers AS SELECT 42 AS v;
SELEC broken;
CREATE TABLE more AS SELECT 'a;b' AS w;
SELECT 'qod-init-credential' FROM;
INSTALL quack;
LOAD quack;
SET GLOBAL quack_authorization_function = 'system.main.no_such_function';
SQL
serve_port="$(port)"
sleep 300 &
parent=$!
pids+=("$parent")
"$node_bin" --port "$serve_port" --bind 127.0.0.1 --secrets-dir "$secrets" --secrets-ephemeral \
  --secret-directory "$work/secret-dir" --node-id "$node_id" --init "$work/init.sql" --parent-pid "$parent" \
  > "$work/serve.out" 2>&1 &
node=$!
pids+=("$node")
if ! wait_for '\[qod-node\] ready' "$work/serve.out"; then
  fail "serve: node never became ready"
  cat "$work/serve.out"
else
  # The two syntax errors fail; the rest, the override of the screen included, run.
  grep -q '5 of 7 statement(s) ran' "$work/serve.out" || fail "serve: expected 5 of 7 init statements to run"
  grep -q 'statement 4 of 7 failed: Parser Error: syntax error' "$work/serve.out" ||
    fail "serve: the failing statement's error was not reported"
  # DuckDB's error names the statement's text after `LINE 1:`; init SQL carries credentials.
  if grep -q -e 'qod-init-credential' -e 'LINE 1:' "$work/serve.out"; then
    fail "serve: a failing init statement's text reached the output"
  fi
  grep -q "serving on" "$work/serve.out" || fail "serve: no listen url reported"
  if grep -q -e "$token" -e "$node_key" "$work/serve.out"; then
    fail "serve: a secret reached the output"
  fi
  [[ ! -e "$secrets" ]] || fail "serve: the ephemeral secrets directory was not removed"

  if [[ -n "${DUCKDB_CLI:-}" ]]; then
    # One quack_query is one client connection with one PREPARE.
    quack_query() {
      DUCKDB_AGENT_MODE=0 HOME="$work/client-home" "$DUCKDB_CLI" -noheader -list \
        -c "INSTALL quack; LOAD quack; FROM quack_query('quack:127.0.0.1:$serve_port', '$1', token := '$token');" 2>&1
    }
    mkdir -p "$work/client-home"
    # A table that does not exist: only the parse screen refuses before binding, and quack words that
    # refusal "Authorization callback failed". Without the screen binding runs first (a catalog
    # error), and for a table that exists the post_bind backstop refuses with no such prefix.
    quack_query "SELECT v FROM memory.main.no_such_table" > "$work/no-handle.out"
    grep -Eq 'Authorization callback failed: .*no verified principal' "$work/no-handle.out" ||
      { fail "quack: a statement without a session handle was not refused by the parse screen"; cat "$work/no-handle.out"; }
    if [[ -n "$handle_tool" ]]; then
      handle="$("$handle_tool" "$node_key" "$node_id" u-test 60000)"
      quack_query "CALL system.main.qod_session(''$handle'')" > "$work/prelude.out"
      grep -qx 'true' "$work/prelude.out" || { fail "quack: a valid prelude did not install"; cat "$work/prelude.out"; }
      quack_query "CALL system.main.qod_session(''$handle'')" > "$work/replay.out"
      grep -q 'already used' "$work/replay.out" || { fail "quack: a replayed handle was not refused"; cat "$work/replay.out"; }
      foreign="$("$handle_tool" "$node_key" quack-other-node u-test 60000)"
      quack_query "CALL system.main.qod_session(''$foreign'')" > "$work/foreign.out"
      grep -q 'another node' "$work/foreign.out" || { fail "quack: a foreign-node handle was not refused"; cat "$work/foreign.out"; }
    else
      echo "SKIP: quack session handle checks (valid, replayed, foreign-node): no handle_tool argument"
    fi
  else
    echo "SKIP: quack identity checks (refusal without a handle, session handles): DUCKDB_CLI is not set"
  fi

  kill_quietly "$parent"
  if wait_exit "$node"; then
    [[ "$exit_status" -eq 0 ]] || fail "parent watch: exit $exit_status, expected 0"
    grep -q 'stopping: parent process' "$work/serve.out" || fail "parent watch: no 'stopping: parent process'"
  else
    fail "parent watch: the node outlived its parent"
  fi
fi

# --- serve: init SQL on stdin, SIGTERM stops the node ---------------------------------------------
printf 'CREATE TABLE piped AS SELECT 7 AS v;\nSELECT * FROM piped;' |
  "$node_bin" --port "$(port)" --bind 127.0.0.1 --secrets-dir "$(new_secrets token-one)" \
    --secret-directory "$work/secret-dir" --node-id "$node_id" --init - > "$work/term.out" 2>&1 &
term_node=$!
pids+=("$term_node")
if wait_for '\[qod-node\] ready' "$work/term.out"; then
  grep -q '2 of 2 statement(s) ran' "$work/term.out" || fail "stdin init: expected 2 of 2 statements to run"
  kill -TERM "$term_node"
  if wait_exit "$term_node"; then
    [[ "$exit_status" -eq 0 ]] || fail "SIGTERM: exit $exit_status, expected 0"
    grep -q 'stopping: signal' "$work/term.out" || fail "SIGTERM: no 'stopping: signal'"
  else
    fail "SIGTERM: the node did not stop"
  fi
else
  fail "SIGTERM: the node never became ready"
  cat "$work/term.out"
fi

# --- serve: a quack_serve failure is fatal ---------------------------------------------------------
# 192.0.2.1 (TEST-NET-1) is never a local address, so binding it fails. (A busy port is no test: quack
# does not refuse a port another listener holds.)
"$node_bin" --port "$(port)" --bind 192.0.2.1 --secrets-dir "$(new_secrets token-two)" \
  --secret-directory "$work/secret-dir" --node-id "$node_id" > "$work/unbindable.out" 2>&1 &
unbindable=$!
pids+=("$unbindable")
if wait_exit "$unbindable"; then
  [[ "$exit_status" -eq 1 ]] || fail "unbindable address: exit $exit_status, expected 1"
  grep -q 'quack_serve failed' "$work/unbindable.out" || fail "unbindable address: no 'quack_serve failed'"
else
  fail "unbindable address: the node kept running"
  cat "$work/unbindable.out"
fi

if [[ $failures -eq 0 ]]; then
  echo "process_test: all passed"
fi
exit $((failures > 0))
