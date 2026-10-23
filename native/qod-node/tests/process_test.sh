#!/usr/bin/env bash
# Process-level tests for qod-node (POSIX). Run by ctest, or directly:
#
#   process_test.sh <qod-node binary>
#
# With DUCKDB_CLI set to a release duckdb CLI, it also queries the node over quack.
set -uo pipefail

node_bin="${1:?usage: process_test.sh <qod-node binary>}"
work="$(mktemp -d)"
failures=0
pids=()

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

new_secrets() {
  local dir="$work/secrets.$RANDOM"
  mkdir -p "$dir"
  printf '%s' "$1" > "$dir/QOD_NODE_TOKEN"
  echo "$dir"
}

# --- build marker ---------------------------------------------------------------------------------
marker="$("$node_bin" --build-info)"
if [[ ! "$marker" =~ ^qod-node-build-info:protocol=[0-9]+\;duckdb=v[0-9]+\.[0-9]+\.[0-9]+\;duckdb_source=[0-9a-f]+$ ]]; then
  fail "unexpected build marker: $marker"
fi

# --- usage errors ---------------------------------------------------------------------------------
"$node_bin" > "$work/usage.out" 2>&1
status=$?
[[ $status -eq 2 ]] || fail "no arguments: exit $status, expected 2"
grep -q -- '--port is required' "$work/usage.out" || fail "no arguments: missing '--port is required'"

"$node_bin" --port 70000 > /dev/null 2>&1
[[ $? -eq 2 ]] || fail "--port 70000 must be refused"

# --- missing token --------------------------------------------------------------------------------
mkdir -p "$work/empty-secrets"
"$node_bin" --port "$(port)" --secrets-dir "$work/empty-secrets" --secret-directory "$work/secret-dir" \
  > "$work/no-token.out" 2>&1
status=$?
[[ $status -eq 1 ]] || fail "missing token: exit $status, expected 1"
grep -q 'has no QOD_NODE_TOKEN' "$work/no-token.out" || fail "missing token: message not found"

# An ephemeral secrets directory goes even when the token read fails.
mkdir -p "$work/empty-ephemeral"
"$node_bin" --port "$(port)" --secrets-dir "$work/empty-ephemeral" --secrets-ephemeral \
  --secret-directory "$work/secret-dir" > /dev/null 2>&1
[[ ! -e "$work/empty-ephemeral" ]] || fail "missing token: the ephemeral secrets directory was kept"

# --- a stop while starting ends the node at once --------------------------------------------------
# An init FIFO no one writes blocks startup in open(), where only an immediate exit reaches it.
mkfifo "$work/stuck-init"
"$node_bin" --port "$(port)" --bind 127.0.0.1 --secrets-dir "$(new_secrets token-stuck)" \
  --secret-directory "$work/secret-dir" --init "$work/stuck-init" > "$work/stuck-term.out" 2>&1 &
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
  --secret-directory "$work/secret-dir" --init "$work/stuck-init" --parent-pid "$stuck_parent" \
  > "$work/stuck-parent.out" 2>&1 &
orphan=$!
pids+=("$orphan")
sleep 1
kill "$stuck_parent"
if wait_exit "$orphan"; then
  [[ "$exit_status" -eq 1 ]] || fail "parent end while starting: exit $exit_status, expected 1"
  grep -q 'stopping before ready: parent process' "$work/stuck-parent.out" ||
    fail "parent end while starting: no 'stopping before ready: parent process'"
else
  fail "parent end while starting: the node outlived its parent"
fi

# --- serve: best-effort init, token kept out of the output, parent watch ---------------------------
token="token-$RANDOM$RANDOM"
secrets="$(new_secrets "$token")"
cat > "$work/init.sql" <<'SQL'
CREATE TABLE answers AS SELECT 42 AS v;
SELEC broken;
CREATE TABLE more AS SELECT 'a;b' AS w;
SELECT 'qod-init-credential' FROM;
SQL
serve_port="$(port)"
sleep 300 &
parent=$!
pids+=("$parent")
"$node_bin" --port "$serve_port" --bind 127.0.0.1 --secrets-dir "$secrets" --secrets-ephemeral \
  --secret-directory "$work/secret-dir" --init "$work/init.sql" --parent-pid "$parent" \
  > "$work/serve.out" 2>&1 &
node=$!
pids+=("$node")
if ! wait_for '\[qod-node\] ready' "$work/serve.out"; then
  fail "serve: node never became ready"
  cat "$work/serve.out"
else
  grep -q '2 of 4 statement(s) ran' "$work/serve.out" || fail "serve: expected 2 of 4 init statements to run"
  grep -q 'statement 4 of 4 failed: Parser Error: syntax error' "$work/serve.out" ||
    fail "serve: the failing statement's error was not reported"
  # DuckDB's error names the statement's text after `LINE 1:`; init SQL carries credentials.
  if grep -q -e 'qod-init-credential' -e 'LINE 1:' "$work/serve.out"; then
    fail "serve: a failing init statement's text reached the output"
  fi
  grep -q "serving on" "$work/serve.out" || fail "serve: no listen url reported"
  if grep -q "$token" "$work/serve.out"; then
    fail "serve: the token reached the output"
  fi
  [[ ! -e "$secrets" ]] || fail "serve: the ephemeral secrets directory was not removed"

  if [[ -n "${DUCKDB_CLI:-}" ]]; then
    "$DUCKDB_CLI" -noheader -list > "$work/client.out" 2>&1 <<SQL
INSTALL quack;
LOAD quack;
ATTACH 'quack:127.0.0.1:$serve_port' AS q (TYPE quack, TOKEN '$token');
FROM quack_query_by_name('q', 'SELECT v FROM memory.main.answers');
SQL
    grep -qx '42' "$work/client.out" || { fail "quack: could not read the init table"; cat "$work/client.out"; }
  fi

  kill "$parent"
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
    --secret-directory "$work/secret-dir" --init - > "$work/term.out" 2>&1 &
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
  --secret-directory "$work/secret-dir" > "$work/unbindable.out" 2>&1 &
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
