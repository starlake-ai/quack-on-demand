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
# The init marker nonce every test node gets (QOD_INIT_NONCE).
init_nonce="0a1b2c3d4e5f60718293a4b5c6d7e8f9"

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
  printf '%s' "${3-$init_nonce}" > "$dir/QOD_INIT_NONCE"
  echo "$dir"
}

# --- build marker ---------------------------------------------------------------------------------
marker="$("$node_bin" --build-info)"
if [[ ! "$marker" =~ ^qod-node-build-info:protocol=4\;duckdb=v[0-9]+\.[0-9]+\.[0-9]+\;duckdb_source=[0-9a-f]+$ ]]; then
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

# --- listings: every function and setting a node resolves, one JSON element per line --------------
"$node_bin" --list-functions > "$work/functions.json" 2> "$work/functions.err"
status=$?
[[ $status -eq 0 ]] || fail "--list-functions: exit $status"
[[ "$(head -1 "$work/functions.json")" == "[" ]] || fail "--list-functions: not one element per line"
grep -qF '{"aliasOf":null,"name":"qod_session","schema":"main","type":"table"}' "$work/functions.json" ||
  fail "--list-functions: qod_session is not listed"
"$node_bin" --list-settings > "$work/settings.json" 2> "$work/settings.err"
status=$?
[[ $status -eq 0 ]] || fail "--list-settings: exit $status"
grep -qF '{"kind":"alias","name":"memory_limit","target":"max_memory"}' "$work/settings.json" ||
  fail "--list-settings: the memory_limit alias is not listed"

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

# A missing or malformed init marker nonce stops the node at start, never naming the value.
refuses_nonce() {
  local label="$1" value="$2" message="$3" status
  local dir
  dir="$(new_secrets tok-bad-nonce "$node_key" "$value")"
  [[ "$value" == "<none>" ]] && rm -f "$dir/QOD_INIT_NONCE"
  "$node_bin" --port "$(port)" --secrets-dir "$dir" \
    --secret-directory "$work/secret-dir" --node-id "$node_id" > "$work/bad-nonce.out" 2>&1
  status=$?
  [[ $status -eq 1 ]] || fail "$label init nonce: exit $status, expected 1"
  grep -q "$message" "$work/bad-nonce.out" || fail "$label init nonce: '$message' not found"
}
refuses_nonce "missing" "<none>" "has no QOD_INIT_NONCE"
refuses_nonce "short" "abcdef" "QOD_INIT_NONCE is not 32 to 128 lowercase hex digits"
refuses_nonce "uppercase" "0A1B2C3D4E5F60718293A4B5C6D7E8F9" "QOD_INIT_NONCE is not 32 to 128"

# A malformed decide limit stops the node at start, naming the variable, never its value.
QOD_AUTHZ_DECIDE_TIMEOUT_MS=qodbadlimit "$node_bin" --port "$(port)" --secrets-dir "$(new_secrets tok-bad-limit)" \
  --secret-directory "$work/secret-dir" --node-id "$node_id" > "$work/bad-limit.out" 2>&1
status=$?
[[ $status -eq 1 ]] || fail "malformed QOD_AUTHZ_DECIDE_TIMEOUT_MS: exit $status, expected 1"
grep -q 'QOD_AUTHZ_DECIDE_TIMEOUT_MS must be a number' "$work/bad-limit.out" ||
  fail "malformed QOD_AUTHZ_DECIDE_TIMEOUT_MS: message not found"
! grep -q 'qodbadlimit' "$work/bad-limit.out" || fail "malformed QOD_AUTHZ_DECIDE_TIMEOUT_MS: the value reached the output"
QOD_AUTHZ_CACHE_ENTRIES=-1 "$node_bin" --port "$(port)" --secrets-dir "$(new_secrets tok-bad-entries)" \
  --secret-directory "$work/secret-dir" --node-id "$node_id" > "$work/bad-entries.out" 2>&1
status=$?
[[ $status -eq 1 ]] || fail "malformed QOD_AUTHZ_CACHE_ENTRIES: exit $status, expected 1"
grep -q 'QOD_AUTHZ_CACHE_ENTRIES must be a number' "$work/bad-entries.out" ||
  fail "malformed QOD_AUTHZ_CACHE_ENTRIES: message not found"

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

# --- serve: the extension settings are fixed before the database opens ----------------------------
# Off unless a flag turns one on (decision 17). The init SQL tries to turn both allow_* settings on:
# DuckDB refuses while the database runs, so neither can a pool's or a database's initSql. The node
# still loads quack (strict) with autoinstall off: it installs it explicitly.
ext_settings() {
  local label="$1" expected="$2"
  shift 2
  printf "SET allow_community_extensions = true;\nSET allow_unsigned_extensions = true;\nCOPY (SELECT current_setting('allow_community_extensions') AS c, current_setting('allow_unsigned_extensions') AS u, current_setting('autoinstall_known_extensions') AS a) TO '%s' (HEADER false);\n" "$work/ext-$label.csv" |
    "$node_bin" --port "$(port)" --bind 127.0.0.1 --secrets-dir "$(new_secrets "token-ext-$label")" \
      --secret-directory "$work/secret-dir" --node-id "$node_id" --init - "$@" > "$work/ext-$label.out" 2>&1 &
  local ext_node=$!
  pids+=("$ext_node")
  if ! wait_for '\[qod-node\] ready' "$work/ext-$label.out"; then
    fail "extensions $label: the node never became ready"
    cat "$work/ext-$label.out"
    return
  fi
  grep -q '1 of 3 statement(s) ran' "$work/ext-$label.out" ||
    fail "extensions $label: the init SQL turned an allow_* setting on"
  grep -q 'Cannot change allow_community_extensions setting while database is running' "$work/ext-$label.out" ||
    fail "extensions $label: no refusal of SET allow_community_extensions"
  [[ "$(tr -d '\r\n' < "$work/ext-$label.csv")" == "$expected" ]] ||
    fail "extensions $label: settings $(tr -d '\r\n' < "$work/ext-$label.csv"), expected $expected"
  kill -TERM "$ext_node"
  wait_exit "$ext_node" || fail "extensions $label: the node did not stop"
}
ext_settings default "false,false,false"
ext_settings all "true,true,true" --allow-community-extensions --allow-unsigned-extensions --autoinstall-known-extensions

# --- serve: a tenant's initSql runs under the allowlist, the rest of the init SQL as written ------
# Task 20: the untrusted section's SET runs, its CREATEs and its COPY are skipped (each named by its
# position, never by its text), the node still serves, and the trusted SQL after the section runs the
# same kind of statement unrestricted. The COPY at the end records what the init did.
cat > "$work/tenant-init.sql" <<SQL
CREATE TABLE trusted_made AS SELECT 1 AS v;
-- qod:untrusted-begin $init_nonce pool
SET memory_limit = '1GB';
CREATE SECURE VIEW tenant_leak AS SELECT * FROM read_text('/etc/hosts');
CREATE OR REPLACE SECRET tenant_s3 (TYPE s3, KEY_ID 'AKIAQODPROCMARKER', SECRET 'x');
COPY (SELECT 1) TO '$work/tenant-copy.csv';
INSTALL x FROM 'http://example.com/repo';
SET threads = 3;
-- qod:untrusted-end $init_nonce
COPY (SELECT current_setting('memory_limit') AS m, current_setting('threads') AS t, (SELECT count(*) FROM duckdb_views() WHERE view_name = 'tenant_leak') AS leak, (SELECT count(*) FROM duckdb_secrets() WHERE name = 'tenant_s3') AS secrets, (SELECT count(*) FROM duckdb_tables() WHERE table_name = 'trusted_made') AS trusted) TO '$work/tenant-init.csv' (HEADER false);
SQL
"$node_bin" --port "$(port)" --bind 127.0.0.1 --secrets-dir "$(new_secrets token-tenant)" \
  --secret-directory "$work/secret-dir" --node-id "$node_id" --init - < "$work/tenant-init.sql" \
  > "$work/tenant.out" 2>&1 &
tenant_node=$!
pids+=("$tenant_node")
if wait_for '\[qod-node\] ready' "$work/tenant.out"; then
  for n in 2 3 4 5; do
    grep -q "tenant pool initSql: statement $n of 6 skipped: " "$work/tenant.out" ||
      fail "tenant initSql: statement $n was not skipped"
  done
  grep -q 'tenant pool initSql: 2 of 6 statement(s) ran under the tenant initSql allowlist' "$work/tenant.out" ||
    fail "tenant initSql: expected 2 of 6 statements to run"
  if grep -q -e 'AKIAQODPROCMARKER' -e '/etc/hosts' -e 'example.com' "$work/tenant.out"; then
    fail "tenant initSql: a skipped statement's text reached the output"
  fi
  [[ ! -e "$work/tenant-copy.csv" ]] || fail "tenant initSql: the refused COPY wrote its file"
  [[ "$(tr -d '\r\n' < "$work/tenant-init.csv" 2>/dev/null)" == "953.6 MiB,3,0,0,1" ]] ||
    fail "tenant initSql: recorded $(tr -d '\r\n' < "$work/tenant-init.csv" 2>/dev/null), expected 953.6 MiB,3,0,0,1"
  kill -TERM "$tenant_node"
  wait_exit "$tenant_node" || fail "tenant initSql: the node did not stop"
else
  fail "tenant initSql: the node never became ready"
  cat "$work/tenant.out"
fi

# --- --check-init: the same verdict, as JSON, for the manager's write-time check -----------------
# The untrusted section of the init above, judged without running: the positions the node skipped.
sed -n '/^-- qod:untrusted-begin/,/^-- qod:untrusted-end/p' "$work/tenant-init.sql" | sed '1d;$d' |
  "$node_bin" --check-init > "$work/check.json" 2> "$work/check.err"
status=$?
[[ $status -eq 0 ]] || fail "--check-init: exit $status"
check="$(cat "$work/check.json")"
[[ "$check" == '{"refused":[{"reason":"'*'","statement":2},{"reason":"'*'","statement":3},{"reason":"'*'","statement":4},{"reason":"'*'","statement":5}],"statements":6}' ]] ||
  fail "--check-init: unexpected verdict (or one quoting the text): $(tr -cd '[:alnum:][:punct:] ' < "$work/check.json" | sed 's/AKIA[A-Z]*/AKIA.../g')"
if grep -q -e 'AKIAQODPROCMARKER' -e '/etc/hosts' "$work/check.json" "$work/check.err"; then
  fail "--check-init: the verdict quotes a statement's text"
fi
printf 'SET threads = 2;\nRESET memory_limit;\nINSTALL httpfs;\nPRAGMA version;\n' | "$node_bin" --check-init > "$work/check-ok.json"
[[ "$(cat "$work/check-ok.json")" == '{"refused":[],"statements":4}' ]] ||
  fail "--check-init: an allowed initSql was refused: $(cat "$work/check-ok.json")"

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
