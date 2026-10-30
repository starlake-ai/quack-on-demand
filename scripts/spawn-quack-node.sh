#!/usr/bin/env bash
#
# Spawn one Quack node - invoked by LocalQuackBackend.
#
# Usage:
#   spawn-quack-node.sh <port> [<manager-marker>]
#
# <manager-marker> (LocalQuackBackend.managerMarker: the manager's REST port and a per-boot id, e.g.
# `20900-3fa9c1e2`) names the node directory `quack-node.<marker>.XXXXXX`, so stop-jar.sh,
# kill-quack-nodes.sh and `qod stop` can stop one manager's nodes without touching another's. Fleet
# servers and Kubernetes pods pass none.
#
# The non-sensitive metastore connection details come from environment variables that
# LocalQuackBackend.start() sets from CreatePoolRequest.metastore. The
# env-var keys match the UI form field names verbatim:
#
#   pgHost pgPort pgUser               (Postgres for DuckLake catalog)
#   dbName                             (Postgres DB; also the DuckDB catalog alias unless
#                                       catalogAlias is set)
#   catalogAlias                       (optional DuckDB catalog alias the database is ATTACHed
#                                       under; defaults to $dbName. Branch catalogs set it to
#                                       their parent's alias, see TenantDb.catalogAlias)
#   schemaName                         (DuckLake schema under the alias; default `main`.
#                                       MUST differ from the alias or 2-part identifiers
#                                       like "$dbName"."customer" resolve as ambiguous.)
#   dataPath                           (DuckLake data files directory)
#   encrypted                          (`true` for an encrypted database, stamped by the manager)
#
# These are TenantDb.NodeEnvKeys: every launcher passes these metastore keys and no other (a
# metastore is tenant-admin input), so a metastore key this script starts reading must be added
# there too (NodeMetastoreSpec checks this script reads each of them).
#
# The node process is qod-node (native/qod-node), not the DuckDB CLI: it links an official DuckDB
# release library, runs the init SQL this script assembles, and serves over quack. $QOD_NODE_BIN
# names it (an absolute path the launcher sets); otherwise the first `qod-node` on PATH.
#
# Sensitive values NEVER arrive through the environment or the command line: a node user with SQL
# can read getenv() and /proc/<pid>/{environ,cmdline} of the node process and of this script.
# They arrive as one file per key in the directory $QOD_SECRETS_DIR (see SECRET_KEYS below; the
# Scala twin is NodeSecretFiles.Keys). The local and fleet launchers create that directory private
# to the current user (0700, files 0600) and set QOD_SECRETS_DIR_EPHEMERAL=1. This script reads the
# keys it assembles into the init SQL and deletes each file right after; qod-node reads its own keys
# (QOD_NODE_KEYS: the node token and the node key) and removes the directory, which this script also
# removes when it exits. On Kubernetes it is a read-only Secret volume. A key still present in the
# environment is ignored (with a warning) rather than used.
#
# The init SQL reaches qod-node on its stdin (--init -), a pipe it reads once to the end: never on
# disk, and nothing else reaches the node's own connection through it. qod-node runs it statement by
# statement and carries on past a failing one, as a node always has (an unreachable federated source,
# an offline INSTALL). What must not fail silently it does itself, strictly, around that SQL: the
# secret directory first, then loading quack and quack_serve with the node token. It ends when this
# wrapper ends (--parent-pid) or on SIGTERM from LocalQuackBackend.stop, at once while still starting.
#
# qod-node runs in an empty, private (0700) directory of its own, created here and removed when the
# node stops: DuckDB 2.0 replacement-scans an unquoted dotted name as a file (`SELECT * FROM x.csv`
# reads ./x.csv), and an unquoted name cannot hold a path separator, so the working directory is
# the one place that form reaches. Every relative path the node relies on (dataPath, the qod-node
# binary, the spill directory) is made absolute against the directory this script started in.

set -euo pipefail

PORT="${1:?port required}"
MARKER="${2:-}"
if [[ -n "$MARKER" && ! "$MARKER" =~ ^[0-9]+-[0-9a-f]+$ ]]; then
  echo "ERROR: invalid manager marker '$MARKER' (expected <rest-port>-<hex boot id>)" >&2
  exit 1
fi

# The directory relative paths resolve against (the manager's or join process's working directory,
# where duckdb itself used to run). Physical, so it matches the manager JVM's own resolution. A
# trailing slash is stripped so a start dir of `/` yields `/ducklake/...`, not `//ducklake/...`.
START_DIR="$(pwd -P)"
START_DIR="${START_DIR%/}"

# Keep in sync with NodeSecretFiles.Keys (Scala), SECRET_KEYS in spawn-quack-node.ps1 and
# _SECRET_KEYS in cli/src/qod_cli/fleet_join.py; NodeSecretFilesSpec pins all four.
SECRET_KEYS=(pgPassword encryptionKey extraSetupSql objectStoreSql QOD_NODE_TOKEN
  QOD_NODE_AUTHZ_KEY tenantDbInitSql tenantDbInitSqlTrusted tenantPoolInitSql tenantPoolInitSqlTrusted
  QOD_INIT_NONCE QOD_S3_ACCESS_KEY_ID QOD_S3_SECRET_ACCESS_KEY QOD_AZURE_CONNECTION_STRING
  QOD_GCS_KEY_ID QOD_GCS_SECRET)
# The keys qod-node reads from $QOD_SECRETS_DIR itself (the node token, the node key and the init
# marker nonce); this script reads none of them but the nonce, which it never deletes (below).
QOD_NODE_KEYS=" QOD_NODE_TOKEN QOD_NODE_AUTHZ_KEY QOD_INIT_NONCE "
# qod-node removes an ephemeral secrets directory once it has read its keys. This script removes it
# too, on every exit, so no exit before the launch (a failed check, a dry run) leaves the node token
# on disk; by the time it exits, its qod-node has read the directory or never will (cleanup below
# stops qod-node first). TERM and INT exit through the EXIT trap until cleanup's own traps replace
# these.
dispose_secrets_dir() {
  if [[ -n "${QOD_SECRETS_DIR:-}" && "${QOD_SECRETS_DIR_EPHEMERAL:-}" == "1" ]]; then
    rm -rf -- "$QOD_SECRETS_DIR" 2>/dev/null || true
  fi
}
trap dispose_secrets_dir EXIT
trap 'exit 143' TERM INT
for key in "${SECRET_KEYS[@]}"; do
  if [[ -n "${!key+x}" ]]; then
    echo "WARN: ignoring $key from the environment; secrets are read from \$QOD_SECRETS_DIR only" >&2
    unset "$key"
  fi
done
if [[ -z "${QOD_SECRETS_DIR:-}" || ! -s "$QOD_SECRETS_DIR/QOD_NODE_TOKEN" ]]; then
  echo "ERROR: no node token: \$QOD_SECRETS_DIR/QOD_NODE_TOKEN is missing or empty" >&2
  exit 1
fi
# Absolute, since qod-node starts in its own directory.
[[ "$QOD_SECRETS_DIR" == /* ]] || QOD_SECRETS_DIR="$START_DIR/$QOD_SECRETS_DIR"
for key in "${SECRET_KEYS[@]}"; do
  [[ "$QOD_NODE_KEYS" == *" $key "* ]] && continue
  if [[ -f "$QOD_SECRETS_DIR/$key" ]]; then
    # read -d '' slurps the whole file (newlines included); it returns 1 at EOF by design.
    IFS= read -r -d '' "$key" < "$QOD_SECRETS_DIR/$key" || true
    if [[ "${QOD_SECRETS_DIR_EPHEMERAL:-}" == "1" ]]; then
      rm -f -- "$QOD_SECRETS_DIR/$key"
    fi
  fi
done
# This spawn's init marker nonce (a fresh one per start, from the launcher): it goes into both lines
# marking a tenant's initSql section, and qod-node, which reads the same file, honours only marker
# lines carrying it. Left in place for qod-node.
INIT_NONCE=""
if [[ -f "$QOD_SECRETS_DIR/QOD_INIT_NONCE" ]]; then
  IFS= read -r INIT_NONCE < "$QOD_SECRETS_DIR/QOD_INIT_NONCE" || true
fi
# Plain shell variables from here on: nothing below exports them, so neither qod-node nor psql
# inherits them.
export -n "${SECRET_KEYS[@]}" INIT_NONCE QOD_SECRETS_DIR QOD_SECRETS_DIR_EPHEMERAL 2>/dev/null || true

pgHost="${pgHost:-localhost}"
pgPort="${pgPort:-5432}"
pgUser="${pgUser:-postgres}"
pgPassword="${pgPassword:-azizam}"
dbName="${dbName:-db1}"
catalogAlias="${catalogAlias:-$dbName}"
schemaName="${schemaName:-main}"
dataPath="${dataPath:-/Users/hayssams/git/public/quack-on-demand/ducklake/$dbName}"

kind="${kind:?kind env var is required (ducklake | duckdb-file | memory)}"
case "$kind" in
  ducklake|duckdb-file|memory) ;;
  *)
    echo "fatal: unknown kind='$kind' (expected: ducklake | duckdb-file | memory)" >&2
    exit 92
    ;;
esac

if [[ "$schemaName" == "$catalogAlias" ]]; then
  echo "ERROR: schemaName ($schemaName) must differ from the catalog alias ($catalogAlias)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$catalogAlias\".<table> as" >&2
  echo "       ambiguous when a catalog and a schema share a name." >&2
  exit 1
fi

# Detect whether dataPath points at a remote object store. DuckLake accepts
# s3:// (covers AWS S3, SeaweedFS, MinIO, R2, GCS via the S3-interop endpoint)
# and azure:// / abfss:// when the matching DuckDB extension is loaded. For
# remote schemes we skip the local mkdir (the parent dir doesn't exist on the
# container fs) and emit the SQL needed to install httpfs/azure + a SECRET so
# the ATTACH below can read/write parquet against the bucket.
#
# The data path signs with credentials scoped to it alone (spec decision 23): the database's own
# objectStore (objectStoreSql's qod_db_store) or else qod_data_s3 below, on the manager's QOD_S3_*
# keys or, with neither, on the instance's AWS credential chain (the aws extension). No unscoped s3
# secret exists on a node: a user's s3:// URL signs with that user's own connection secret
# (qod_uc_<id8>, which qod-node installs per connection) or with nothing. A key a secret leaves out
# is filled from the s3_* settings, so every key is named; qod-node resets the GLOBAL ones the
# environment filled whenever httpfs loads. The scopes follow ObjectStoreSecret.dataScopes: a
# duckdb-file path itself (DuckDB matches a scope as a string prefix, so it also reaches every name
# beginning with the file's; the manager's deny set refuses those names); a branch prefix
# <parent>__br_<id8>/ its parent's prefix and its own (it
# reads the parent's Parquet in place); any other path itself plus one '/' and its branches' prefix
# <path>__br_ (a merge node attaches a branch beside its parent).
IS_REMOTE=0
STORAGE_SQL=""
# The quote rides a variable: inside double quotes, bash 3.2 (macOS) keeps the backslashes of an
# escaped quote in the replacement.
sql_quote() { local q="'"; printf '%s' "${1//$q/$q$q}"; }
case "$dataPath" in
  s3://*|s3a://*|gs://*|r2://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL httpfs;"$'\n'"LOAD httpfs;"
    if [[ -z "${objectStoreSql:-}" ]]; then
      if [[ "$kind" == "duckdb-file" ]]; then
        data_scopes="'$(sql_quote "$dataPath")'"
      elif [[ "$dataPath" =~ ^(.*)__br_[0-9a-f]{8}/?$ ]]; then
        data_scopes="'$(sql_quote "${BASH_REMATCH[1]}/")', '$(sql_quote "${dataPath%/}/")'"
      else
        data_scopes="'$(sql_quote "${dataPath%/}/")', '$(sql_quote "${dataPath%/}__br_")'"
      fi
      # USE_SSL is spliced into SQL unquoted: true or false only.
      s3_use_ssl="$(printf '%s' "${QOD_S3_USE_SSL:-true}" | tr '[:upper:]' '[:lower:]')"
      if [[ "$s3_use_ssl" != "true" && "$s3_use_ssl" != "false" ]]; then
        echo "ERROR: QOD_S3_USE_SSL must be true or false." >&2
        exit 1
      fi
      # Strip http(s):// from the endpoint - DuckDB wants "host:port".
      ep="${QOD_S3_ENDPOINT:-}"
      ep="${ep#http://}"; ep="${ep#https://}"; ep="${ep%/}"
      if [[ -n "${QOD_S3_ACCESS_KEY_ID:-}" && -n "${QOD_S3_SECRET_ACCESS_KEY:-}" ]]; then
        STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET qod_data_s3 (
  TYPE s3,
  KEY_ID '$(sql_quote "$QOD_S3_ACCESS_KEY_ID")',
  SECRET '$(sql_quote "$QOD_S3_SECRET_ACCESS_KEY")',
  SESSION_TOKEN '',
  REGION '$(sql_quote "${QOD_S3_REGION:-us-east-1}")',
  ENDPOINT '$(sql_quote "$ep")',
  URL_STYLE '$(sql_quote "${QOD_S3_URL_STYLE:-path}")',
  USE_SSL ${s3_use_ssl},
  KMS_KEY_ID '',
  URL_COMPATIBILITY_MODE false,
  REQUESTER_PAYS false,
  SCOPE [$data_scopes]
);"
      else
        # The instance's own credentials (an instance role, IRSA, an AWS_* environment, a profile):
        # the chain supplies the identity, so no key, secret or session token is named here, and
        # the region only when the operator sets one. VALIDATION 'none': a node without instance
        # credentials still starts (unsigned requests reach a public bucket); REFRESH 'auto':
        # instance credentials rotate.
        region_line=""
        if [[ -n "${QOD_S3_REGION:-}" ]]; then
          region_line="
  REGION '$(sql_quote "$QOD_S3_REGION")',"
        fi
        STORAGE_SQL="$STORAGE_SQL
INSTALL aws;
LOAD aws;
CREATE OR REPLACE SECRET qod_data_s3 (
  TYPE s3,
  PROVIDER credential_chain,
  VALIDATION 'none',
  REFRESH 'auto',$region_line
  ENDPOINT '$(sql_quote "$ep")',
  URL_STYLE '$(sql_quote "${QOD_S3_URL_STYLE:-vhost}")',
  USE_SSL ${s3_use_ssl},
  URL_COMPATIBILITY_MODE false,
  SCOPE [$data_scopes]
);"
      fi
    fi
    ;;
  az://*|azure://*|abfss://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL azure;"$'\n'"LOAD azure;"
    if [[ -n "${QOD_AZURE_CONNECTION_STRING:-}" ]]; then
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '${QOD_AZURE_CONNECTION_STRING}'
);"
    fi
    ;;
esac

# A relative local dataPath (the bundled default `./ducklake/tpch` is one) used to resolve against
# duckdb's working directory; resolve it against the starting directory instead, since duckdb no
# longer runs there. DuckLake refuses a DATA_PATH that differs from the one recorded at catalog
# creation (still the relative spelling), so the ATTACH then passes OVERRIDE_DATA_PATH, which applies
# to this attach only and rewrites nothing in the catalog.
DATA_PATH_OVERRIDE=""
if [[ "$IS_REMOTE" == "0" && -n "$dataPath" && "$dataPath" != /* ]]; then
  while [[ "$dataPath" == ./* ]]; do dataPath="${dataPath#./}"; done
  dataPath="$START_DIR/$dataPath"
  DATA_PATH_OVERRIDE=", OVERRIDE_DATA_PATH true"
fi
# The starting directory is not validated like the rest of the metastore: double any quote.
data_path_sql=${dataPath//\'/\'\'}

# mkdir behaviour differs by kind: for `ducklake`, dataPath is a parquet
# DIRECTORY, so mkdir -p on the path itself is correct. For `duckdb-file`,
# dataPath is a .duckdb FILE path - mkdir -p on the path itself would create
# a directory there and the later `ATTACH '$dataPath'` fails with "Is a
# directory"; only the parent directory needs to exist. For `memory`, there
# is no on-disk path at all.
# Not dry-run-gated: SpawnScriptEncryptionSpec supplies a writable temp dataPath, so this
# exercises the same filesystem preparation a real spawn does.
if [[ "$IS_REMOTE" == "0" ]]; then
  case "$kind" in
    ducklake)
      mkdir -p "$dataPath"
      ;;
    duckdb-file)
      if [[ -n "$dataPath" ]]; then
        mkdir -p "$(dirname "$dataPath")"
      fi
      ;;
    memory)
      : # no on-disk dataPath to create
      ;;
  esac
fi

# Resolve qod-node. $QOD_NODE_BIN wins (an absolute path the launcher sets to the provisioned
# binary); otherwise the first `qod-node` on PATH. Mirrors spawn-quack-node.ps1 so the manager can
# pin the binary without relying on PATH inheritance reaching this spawned process.
# The presence check is skipped in dry-run mode: SpawnScriptEncryptionSpec only exercises INIT_SQL
# assembly, which is pure string building and needs no binary at all.
QOD_NODE="${QOD_NODE_BIN:-qod-node}"
if [[ "${QOD_SPAWN_DRY_RUN:-}" != "1" ]]; then
  command -v "$QOD_NODE" >/dev/null 2>&1 || {
    echo "ERROR: qod-node not found (QOD_NODE_BIN='${QOD_NODE_BIN:-}', 'qod-node' not on PATH)" >&2
    exit 1
  }
  # Absolute, since qod-node starts in its own directory (a relative QOD_NODE_BIN or PATH entry
  # would otherwise resolve there).
  QOD_NODE="$(command -v "$QOD_NODE")"
  [[ "$QOD_NODE" == /* ]] || QOD_NODE="$START_DIR/$QOD_NODE"
fi

# The spill directory a stale node directory records (spill.path), removed only when it is one this
# script makes: `qod-node.XXXXXX` directly under a spill root of this host (see SPILL_ROOT below),
# a real directory owned by this user, compared on physical paths. Anything else (a planted path, a
# spill root of another starting directory) is left in place; the `tmp` fallback inside the node
# directory goes with that directory.
remove_stale_spill() {
  local sp name parent root
  sp="$(cat "$1/spill.path" 2>/dev/null || true)"
  name="${sp##*/}"
  [[ "$name" =~ ^qod-node\.[A-Za-z0-9]{6}$ ]] || return 0
  [[ -d "$sp" && ! -L "$sp" && -O "$sp" ]] || return 0
  parent="$(cd -P -- "${sp%/*}" 2>/dev/null && pwd -P)" || return 0
  for root in /duckdb-tmp "$START_DIR/.tmp"; do
    root="$(cd -P -- "$root" 2>/dev/null && pwd -P)" || continue
    if [[ "$parent" == "$root" ]]; then
      rm -rf -- "$parent/$name" 2>/dev/null || true
      return 0
    fi
  done
  return 0
}

# Sweep node directories left by an earlier spawn whose script died on SIGKILL (which skips the
# trap below): a node directory holds no secret once psql is done with its passfile, but a spill
# directory can be large. TMPDIR is often the shared, sticky /tmp, where any local user can create a
# `quack-node.*` entry, so an entry is considered only when it is a real directory (not a symlink)
# owned by this user, and only once its recorded owner pid is provably gone: `kill -0` also fails on
# another user's live process (EPERM), so `ps` has the last word. A live owner's directory (this manager's or another's) and one
# still being created (no pid file yet) are left alone. Every delete is best effort: a stray entry
# must never abort this spawn under `set -e`.
for d in "${TMPDIR:-/tmp}"/quack-node.*; do
  [[ -d "$d" && ! -L "$d" && -O "$d" ]] || continue
  op="$d/owner.pid"
  [[ -f "$op" && ! -L "$op" ]] || continue
  owner="$(cat "$op" 2>/dev/null || true)"
  [[ "$owner" =~ ^[0-9]+$ ]] || continue
  if kill -0 "$owner" 2>/dev/null || ps -p "$owner" >/dev/null 2>&1; then
    continue
  fi
  remove_stale_spill "$d"
  # The read-only secret directory holds its marker file: writable again before rm -rf. Never
  # through a symlink, which would change the mode of whatever it points at.
  if [[ -d "$d/secrets" && ! -L "$d/secrets" ]]; then
    chmod 700 "$d/secrets" 2>/dev/null || true
  fi
  rm -rf -- "$d" 2>/dev/null || true
done

# Per-node private state, all removed by cleanup() when the node stops: NODE_DIR (0700, from mktemp)
# holds the psql passfile while it is needed, owner.pid / spill.path for the stale sweep above,
# WORK_DIR, the empty directory qod-node runs in, and SECRET_DIR, the read-only home of DuckDB's
# unreachable secret directory. Created in dry-run mode too, so the SQL below names the same
# directories a real spawn would.
NODE_DIR="$(mktemp -d "${TMPDIR:-/tmp}"/quack-node.${MARKER:+$MARKER.}XXXXXX)"
SPILL_DIR=""
NODE_PID=""
cleanup() {
  if [[ -n "$NODE_PID" ]]; then
    kill -TERM "$NODE_PID" 2>/dev/null || true
    wait "$NODE_PID" 2>/dev/null || true
  fi
  chmod 700 "$NODE_DIR/secrets" 2>/dev/null || true
  rm -rf -- "$NODE_DIR" ${SPILL_DIR:+"$SPILL_DIR"}
  dispose_secrets_dir
}
STOPPING=""
trap 'STOPPING=1; cleanup' TERM INT
trap cleanup EXIT
printf '%s' "$$" > "$NODE_DIR/owner.pid"
WORK_DIR="$NODE_DIR/cwd"
mkdir -m 700 "$WORK_DIR"
# DuckDB loads every persistent secret of its secret directory on first secret use, and the default
# is the OS user's ~/.duckdb/stored_secrets, which would put an operator's own cloud keys on every
# node. The node's secret directory is SECRET_PATH instead, a path BELOW the regular file `none` of
# the read-only (0500) directory SECRET_DIR: it can never exist as a directory, so nothing loads and
# CREATE PERSISTENT SECRET fails ("Failed to create directory ...: File exists"), for root too.
# Persistent secrets stay enabled: with allow_persistent_secrets = false, DuckDB 2.0 refuses every
# postgres ATTACH that names no SECRET, inline credentials included. The marker sits in the 0500
# directory because a marker in a writable one can be replaced by a directory through
# `COPY ... (PARTITION_BY ..., OVERWRITE)`. Root with CAP_DAC_OVERRIDE could still do that in two
# deliberate steps (the warning below names it; anything stored then stays in NODE_DIR). Absolute,
# since qod-node runs in WORK_DIR. qod-node sets it (--secret-directory) before anything else runs.
SECRET_DIR="$NODE_DIR/secrets"
[[ "$SECRET_DIR" == /* ]] || SECRET_DIR="$START_DIR/$SECRET_DIR"
mkdir -m 700 "$SECRET_DIR"
: > "$SECRET_DIR/none"
chmod 400 "$SECRET_DIR/none"
chmod 500 "$SECRET_DIR"
SECRET_PATH="$SECRET_DIR/none/s"
# A real write probe, not `[[ -w ]]`: glibc may report uid 0 as able to write anything even with
# every capability dropped.
if ( : > "$SECRET_DIR/.probe" ) 2>/dev/null; then
  rm -f -- "$SECRET_DIR/.probe"
  echo "WARN: the node's secret directory $SECRET_DIR is writable (root with CAP_DAC_OVERRIDE?); only its marker file keeps persistent secrets off this node" >&2
fi
# DuckDB's spill directory defaults to `.tmp` under its working directory, which no longer persists
# across nodes. Give each node its own, on the filesystem it spilled to before: the emptyDir
# KubernetesQuackBackend mounts on every pod when present, else `.tmp` of the starting directory
# (not TMPDIR, which is often RAM-backed on Linux). Per node, so that no node can read another's
# spill files. NODE_DIR when neither works.
if [[ -d /duckdb-tmp && -w /duckdb-tmp ]]; then
  SPILL_ROOT=/duckdb-tmp
else
  SPILL_ROOT="$START_DIR/.tmp"
fi
SPILL_DIR="$(mkdir -p "$SPILL_ROOT" 2>/dev/null && mktemp -d "$SPILL_ROOT/qod-node.XXXXXX" 2>/dev/null)" ||
  SPILL_DIR=""
if [[ -z "$SPILL_DIR" ]]; then
  SPILL_DIR="$NODE_DIR/tmp"
  mkdir -m 700 "$SPILL_DIR"
fi
# Recorded so the stale sweep of a later spawn can remove this spill dir if our script is SIGKILLed.
printf '%s' "$SPILL_DIR" > "$NODE_DIR/spill.path"

# Ensure the Postgres database $dbName exists. Connects to PG_ADMIN_DB (default
# `postgres`) as admin and runs CREATE DATABASE if missing. Skipped when psql
# isn't available - the DuckLake ATTACH below will fail loudly in that case.
# Only needed for kind=ducklake.
# Still dry-run-gated (unlike mkdir above): SpawnScriptEncryptionSpec's ducklake cases use a
# fake pgHost, and a real psql invocation would attempt a network connection to it - DNS
# resolution of an unresolvable host has no bounded latency guarantee, so this would make the
# test's runtime depend on the test machine's network/resolver behavior.
if [[ "$kind" == "ducklake" && "${QOD_SPAWN_DRY_RUN:-}" != "1" ]] && command -v psql >/dev/null 2>&1; then
  ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  # The password reaches psql through a private passfile, never PGPASSWORD: psql's own
  # environment is as readable as the node's while it runs. It sits in NODE_DIR, so cleanup()
  # removes it on any exit (a kill during psql included), not only on the normal path below.
  PGPASS_FILE="$NODE_DIR/pgpass"
  pgpass_escaped="${pgPassword//\\/\\\\}"
  pgpass_escaped="${pgpass_escaped//:/\\:}"
  (umask 077 && printf '*:*:*:*:%s\n' "$pgpass_escaped" > "$PGPASS_FILE")
  EXISTS=$(PGPASSFILE="$PGPASS_FILE" psql -w -h "$pgHost" -p "$pgPort" -U "$pgUser" \
    -d "$ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$dbName'" 2>/dev/null || true)
  if [[ "$EXISTS" != "1" ]]; then
    echo "spawn: creating Postgres database $dbName"
    PGPASSFILE="$PGPASS_FILE" psql -w -h "$pgHost" -p "$pgPort" -U "$pgUser" \
      -d "$ADMIN_DB" -tAc "CREATE DATABASE \"$dbName\"" >/dev/null || {
        echo "WARN: CREATE DATABASE $dbName failed; ATTACH below may fail" >&2
      }
  fi
  rm -f -- "$PGPASS_FILE"
fi

# QOD_NODE_BIND: interface the node listens on. 0.0.0.0 keeps local and K8s nodes unchanged; the
# fleet join process passes the server's advertised data interface so a node never listens on a
# management NIC by accident. DuckDB 2.0 serves HTTPS by default off localhost; qod-node serves plain
# HTTP, which the manager speaks (node TLS: #27).
NODE_BIND="${QOD_NODE_BIND:-0.0.0.0}"

# DuckDB does NOT honour the HTTP_PROXY / HTTPS_PROXY env vars for
# `INSTALL <extension>` downloads - those have to be set via the SQL
# `SET http_proxy=...` setting (duckdb forwards to libcurl through that
# setting only). Without this, behind a corporate proxy the very first
# INSTALL hangs and the node never reaches `quack_serve`. We emit the
# SET lines only when a proxy env var is present, so non-proxied
# environments are unaffected.
PROXY_SQL=""
PROXY_URL="${HTTP_PROXY:-${http_proxy:-${HTTPS_PROXY:-${https_proxy:-}}}}"
if [[ -n "$PROXY_URL" ]]; then
  # DuckDB wants "host:port" without the scheme prefix.
  HOSTPORT="${PROXY_URL#http://}"
  HOSTPORT="${HOSTPORT#https://}"
  HOSTPORT="${HOSTPORT%/}"
  PROXY_SQL="SET http_proxy = '$HOSTPORT';"
fi

# Cgroup-aware memory_limit / threads, K8s pods only (no-op elsewhere).
#
# DuckDB's own defaults read the HOST, not the container: memory_limit
# defaults to ~80% of host RAM (ignores the cgroup limit a K8s
# `resources.limits.memory` actually sets), and threads defaults to the
# CPU count visible via affinity, which K8s's CFS-quota-based
# `resources.limits.cpu` does not reduce (that throttles time slices, it
# doesn't shrink the visible core count without the rarely-used "static"
# CPU manager policy). Left alone, a pod sized smaller than the node
# either gets OOM-killed by the kernel once it exceeds its real cgroup
# limit (DuckDB never thought it was close, so it never spilled), or
# oversubscribes worker threads onto a fraction of a CPU's worth of
# actual CFS-granted time. Reading the cgroup files directly - rather
# than threading a new cpu/memory env var through both backends - stays
# correct for any pool size (fixed tier or custom) with no new plumbing.
# Same pattern as the SF>=10 fix in _load-common.sh's
# cgroup_memory_bytes/default_memory_limit, but tuned differently: that
# script shares its cgroup with the manager JVM during a local demo load
# (hence a conservative 40%), whereas a node pod runs nothing but DuckDB,
# so 85% (in line with general DuckDB memory-tuning guidance) is safe.
# temp_directory is the per-node SPILL_DIR chosen above, on every runtime.
spill_dir_sql=${SPILL_DIR//\'/\'\'}
RESOURCE_SQL="SET temp_directory = '$spill_dir_sql';"$'\n'
if [[ -r /sys/fs/cgroup/memory.max ]]; then
  MEM_BYTES="$(cat /sys/fs/cgroup/memory.max 2>/dev/null)"        # cgroup v2
elif [[ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]]; then
  MEM_BYTES="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)"  # cgroup v1
else
  MEM_BYTES=""
fi
if [[ "$MEM_BYTES" =~ ^[0-9]+$ ]] && (( MEM_BYTES > 0 && MEM_BYTES < 9000000000000000000 )); then
  MEM_MIB=$(( MEM_BYTES / 1024 / 1024 * 85 / 100 ))
  (( MEM_MIB < 256 )) && MEM_MIB=256
  RESOURCE_SQL+="SET memory_limit = '${MEM_MIB}MiB';"$'\n'
fi
if [[ -r /sys/fs/cgroup/cpu.max ]]; then
  read -r CPU_QUOTA CPU_PERIOD < /sys/fs/cgroup/cpu.max              # cgroup v2: "<quota> <period>"
  if [[ "$CPU_QUOTA" =~ ^[0-9]+$ && "$CPU_PERIOD" =~ ^[0-9]+$ && "$CPU_PERIOD" -gt 0 ]]; then
    CPU_THREADS=$(( (CPU_QUOTA + CPU_PERIOD - 1) / CPU_PERIOD ))     # ceil(quota / period)
    (( CPU_THREADS < 1 )) && CPU_THREADS=1
    RESOURCE_SQL+="SET threads = ${CPU_THREADS};"$'\n'
  fi
elif [[ -r /sys/fs/cgroup/cpu/cpu.cfs_quota_us && -r /sys/fs/cgroup/cpu/cpu.cfs_period_us ]]; then
  CPU_QUOTA="$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null)"   # cgroup v1
  CPU_PERIOD="$(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us 2>/dev/null)"
  if [[ "$CPU_QUOTA" =~ ^[0-9]+$ && "$CPU_PERIOD" =~ ^[0-9]+$ && "$CPU_PERIOD" -gt 0 ]]; then
    CPU_THREADS=$(( (CPU_QUOTA + CPU_PERIOD - 1) / CPU_PERIOD ))
    (( CPU_THREADS < 1 )) && CPU_THREADS=1
    RESOURCE_SQL+="SET threads = ${CPU_THREADS};"$'\n'
  fi
fi

# Feed init SQL. Per-kind branching below:
#   ducklake    - INSTALL/ATTACH the DuckLake catalog via a per-dbname
#                 pg_advisory_lock to serialize first-time
#                 __ducklake_metadata creation across concurrent K8s
#                 pod spawns (see guides/ROADMAP.md, issue #3).
#   duckdb-file - ATTACH the on-disk .duckdb file directly. No
#                 advisory lock; per-node file is independent.
#   memory      - DuckDB's built-in 'memory' catalog is the default,
#                 no ATTACH needed. Federation aliases (in extraSetupSql)
#                 are typically the only catalogs available.

# A tenant's initSql as qod-node gets it. The file's name says who wrote it: tenantDbInitSqlTrusted
# and tenantPoolInitSqlTrusted (a superuser or the manager) run as written; tenantDbInitSql and
# tenantPoolInitSql (a tenant admin) go between `-- qod:untrusted-begin <nonce> <label>` and
# `-- qod:untrusted-end <nonce>`, a section qod-node runs under its tenant initSql allowlist,
# statement by statement. <nonce> is this spawn's INIT_NONCE, so no line of any other text (a value
# the manager interpolates into its own SQL included) is a marker. Every line holding the marker
# text, in any case, is also dropped from a tenant's text first (the manager refuses it at write
# time).
strip_init_markers() {
  local rest="$1" line out="" restore_case=1
  shopt -q nocasematch && restore_case=0
  shopt -s nocasematch
  while [[ -n "$rest" ]]; do
    if [[ "$rest" == *$'\n'* ]]; then
      line="${rest%%$'\n'*}"
      rest="${rest#*$'\n'}"
    else
      line="$rest"
      rest=""
    fi
    [[ "$line" == *qod:untrusted-* ]] || out+="$line"$'\n'
  done
  if (( restore_case )); then
    shopt -u nocasematch
  fi
  printf '%s' "$out"
}
# Prints the section for one initSql (nothing for an empty one), newline-terminated. Fails, writing
# nothing, for an untrusted text without a well-formed nonce: it is never run unmarked.
tenant_init_sql() {
  local label="$1" sql trusted="$3"
  sql="$(strip_init_markers "$2")"
  [[ -n "$sql" ]] || return 0
  if [[ "$trusted" == "trusted" ]]; then
    printf '%s\n' "$sql"
  elif [[ "$INIT_NONCE" =~ ^[0-9a-f]{32,128}$ ]]; then
    printf -- '-- qod:untrusted-begin %s %s\n%s\n-- qod:untrusted-end %s\n' "$INIT_NONCE" "$label" "$sql" \
      "$INIT_NONCE"
  else
    echo "ERROR: no well-formed init marker nonce (\$QOD_SECRETS_DIR/QOD_INIT_NONCE) for the $label initSql" >&2
    return 1
  fi
}
# Appends tenant_init_sql's section to INIT_SQL (a command substitution drops the final newline).
append_tenant_init_sql() {
  local section
  if ! section="$(tenant_init_sql "$1" "$2" "$3")"; then
    exit 1
  fi
  if [[ -n "$section" ]]; then
    INIT_SQL+="$section"$'\n'
  fi
}

# Build init SQL piecemeal based on $kind so kind=memory and
# kind=duckdb-file skip DuckLake-specific setup entirely. qod-node runs it after setting the secret
# directory (SECRET_PATH, see above: DuckDB then refuses to change it from any session) and before
# loading quack and serving.
INIT_SQL=""
# The first line: this spawn's nonce, which qod-node compares with the one it reads itself and, on a
# mismatch (or without this line), refuses to run the init SQL at all.
if [[ "$INIT_NONCE" =~ ^[0-9a-f]{32,128}$ ]]; then
  INIT_SQL+="-- qod:init-nonce $INIT_NONCE"$'\n'
fi
INIT_SQL+="$PROXY_SQL"$'\n'
# Cgroup-derived memory_limit / temp_directory / threads (see above) and a fleet assignment's pool
# cpu/memory SETs (resourceSql, the manager's) run right after the proxy settings and before the
# tenant's initSql, so an operator's own "SET memory_limit=..." / "SET threads=..." there still
# wins - DuckDB's SET is a plain reassignment, so the later statement is the one that sticks.
INIT_SQL+="$RESOURCE_SQL"
if [[ -n "${resourceSql:-}" ]]; then
  INIT_SQL+="$resourceSql"$'\n'
fi
# qod-node opens the database with autoinstall_known_extensions off (decision 17), so a statement no
# longer downloads the known extension it needs. Install, without loading, the two QoD's own setup
# reached only that way: httpfs (s3 and http reads, and Iceberg REST catalogs, on a node whose data
# path is local) and avro (Iceberg manifests). Autoloading, still on, loads an installed extension on
# first use. Best effort like every init statement: a node offline with neither installed logs it and
# starts.
INIT_SQL+=$'INSTALL httpfs;\nINSTALL avro;\n'
# Per-database init SQL (the tenant-db's initSql). Runs before the catalog ATTACH, right after the
# proxy and resource settings, and long before qod-node loads quack, so engine-level defaults
# (SET memory_limit, extension INSTALLs) are already in effect when quack starts. The pool's
# initSql and the federation SQL run further down.
append_tenant_init_sql database "${tenantDbInitSqlTrusted:-}" trusted
append_tenant_init_sql database "${tenantDbInitSql:-}" untrusted

# Per-database object-store secret (objectStoreSql env, authored by ObjectStoreSecret.scala).
# A scoped CREATE SECRET for this database's own bucket/credentials, emitted for EVERY kind:
# TenantDb.validate permits a `memory` or `duckdb-file` database to carry an objectStore, and a
# `memory` database of views over s3:// parquet (the `qod serve` remote-prefix case) reads nothing
# without it. Must precede any ATTACH, and runs right before STORAGE_SQL, which adds
# qod_data_s3 only when objectStoreSql is empty.
if [[ -n "${objectStoreSql:-}" ]]; then
  INIT_SQL+="$objectStoreSql"$'\n'
fi

# The data path's storage secret and extensions (STORAGE_SQL, above), for every kind: a duckdb-file
# or memory database on an object store needs its scoped secret as much as a DuckLake one. Before any
# ATTACH.
if [[ -n "$STORAGE_SQL" ]]; then
  INIT_SQL+="$STORAGE_SQL"$'\n'
fi

# Encryption needs OpenSSL, which the httpfs extension provides. Without it DuckDB falls back to
# mbedtls, where 1.4.1+ REFUSES WRITES to an encrypted database file, and the symptom is a silently
# read-only node rather than an error. STORAGE_SQL loads httpfs for remote data paths only, so a
# local encrypted database would miss it.
ENCRYPTION_SQL=""
if [[ "${encrypted:-}" == "true" ]]; then
  ENCRYPTION_SQL=$'INSTALL httpfs;\nLOAD httpfs;\n'
fi
INIT_SQL+="$ENCRYPTION_SQL"

case "$kind" in
  ducklake)
    INIT_SQL+=$'INSTALL ducklake;\nLOAD ducklake;\n'
    INIT_SQL+=$'INSTALL postgres;\nLOAD postgres;\n'
    # The catalog password rides a TEMPORARY secret, never an ATTACH string: DuckDB prints an attach
    # path verbatim in duckdb_databases().path and in connection errors, readable by any SQL user
    # of the node. Both ATTACHes name the secret, and it is dropped right after them (before
    # federation SQL and quack_serve) so no session can attach the catalog again with it. Mirrors
    # CatalogPgSecret.scala.
    # TenantDb.validateSafety already refuses a quote in pgPassword; double one anyway.
    pg_password_sql=${pgPassword//\'/\'\'}
    INIT_SQL+="CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD '$pg_password_sql');"$'\n'
    INIT_SQL+="ATTACH 'host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS qod_init_pg (TYPE postgres, SECRET qod_catalog_pg);"$'\n'
    INIT_SQL+="SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_lock(hashtext(''qod-ducklake-init:$dbName''))');"$'\n'
    INIT_SQL+="ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS \"$catalogAlias\""$'\n'
    # Nodes attach as-is: the manager's DuckLakeInitializer migrates older catalogs once per boot.
    if [[ "${encrypted:-}" == "true" ]]; then
      INIT_SQL+="  (DATA_PATH '$data_path_sql', ENCRYPTED$DATA_PATH_OVERRIDE, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});"$'\n'
    else
      INIT_SQL+="  (DATA_PATH '$data_path_sql'$DATA_PATH_OVERRIDE, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});"$'\n'
    fi
    INIT_SQL+="SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_unlock(hashtext(''qod-ducklake-init:$dbName''))');"$'\n'
    INIT_SQL+=$'DETACH qod_init_pg;\n'
    INIT_SQL+=$'DROP SECRET IF EXISTS qod_catalog_pg;\n'
    INIT_SQL+="USE \"$catalogAlias\";"$'\n'
    INIT_SQL+="CREATE SCHEMA IF NOT EXISTS \"$schemaName\";"$'\n'
    INIT_SQL+="USE \"$catalogAlias\".\"$schemaName\";"$'\n'
    ;;
  duckdb-file)
    if [[ "${encrypted:-}" == "true" ]]; then
      INIT_SQL+="ATTACH '$data_path_sql' AS \"$catalogAlias\" (ENCRYPTION_KEY '$encryptionKey');"$'\n'
    else
      INIT_SQL+="ATTACH '$data_path_sql' AS \"$catalogAlias\";"$'\n'
    fi
    INIT_SQL+="USE \"$catalogAlias\";"$'\n'
    INIT_SQL+="CREATE SCHEMA IF NOT EXISTS \"$schemaName\";"$'\n'
    INIT_SQL+="USE \"$catalogAlias\".\"$schemaName\";"$'\n'
    ;;
  memory)
    : # nothing; DuckDB's built-in 'memory' catalog is the default
    ;;
esac

# The pool's initSql, before the federation SQL, so its PRAGMAs and SETs are in effect before any
# federated source is attached.
append_tenant_init_sql pool "${tenantPoolInitSqlTrusted:-}" trusted
append_tenant_init_sql pool "${tenantPoolInitSql:-}" untrusted
if [[ -n "${extraSetupSql:-}" ]]; then
  INIT_SQL+="$extraSetupSql"$'\n'
fi

# Test seam: print the assembled init SQL and exit without launching qod-node.
# Used by SpawnScriptEncryptionSpec to assert the emitted SQL, which is the only
# testable surface of a script the manager shells out to.
if [[ "${QOD_SPAWN_DRY_RUN:-}" == "1" ]]; then
  printf '%s' "$INIT_SQL"
  exit 0
fi

# QOD_NODE_ID: the node's id, which every session handle qod-node accepts must name. Not secret.
if [[ -z "${QOD_NODE_ID:-}" ]]; then
  echo "ERROR: QOD_NODE_ID is not set (the launcher names the node it starts)" >&2
  exit 1
fi
NODE_ARGS=(--port "$PORT" --bind "$NODE_BIND" --secrets-dir "$QOD_SECRETS_DIR"
  --secret-directory "$SECRET_PATH" --node-id "$QOD_NODE_ID" --parent-pid "$$" --init -)
if [[ "${QOD_SECRETS_DIR_EPHEMERAL:-}" == "1" ]]; then
  NODE_ARGS+=(--secrets-ephemeral)
fi
# Decision 17: qod-node fixes allow_community_extensions, allow_unsigned_extensions and
# autoinstall_known_extensions before its database opens, each off unless the manager's
# quack-on-demand.nodeExtensions turns it on (every launcher sets all three variables). Only the exact
# string "true" turns one on.
if [[ "${QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS:-}" == "true" ]]; then
  NODE_ARGS+=(--allow-community-extensions)
fi
if [[ "${QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS:-}" == "true" ]]; then
  NODE_ARGS+=(--allow-unsigned-extensions)
fi
if [[ "${QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS:-}" == "true" ]]; then
  NODE_ARGS+=(--autoinstall-known-extensions)
fi
# The init SQL goes down a pipe to qod-node's stdin (printf is a builtin: the SQL never reaches an
# argv). qod-node runs in WORK_DIR; the subshell execs it, so $! is qod-node's own pid. A writer still
# blocked on a full pipe ends with EPIPE once qod-node is gone.
printf '%s' "$INIT_SQL" | (cd "$WORK_DIR" && exec "$QOD_NODE" "${NODE_ARGS[@]}") &
NODE_PID=$!

# Block until qod-node is stopped (typically SIGTERM from the manager, which runs the trap).
rc=0
wait "$NODE_PID" || rc=$?
if [[ -n "$STOPPING" ]]; then
  exit 143
fi
# qod-node ended on its own while this wrapper is alive: it only stops by itself when this wrapper
# is gone, so this is a failure (a crashed node, or a strict startup step that failed). Say so and
# exit non-zero, rather than vanishing; the manager then sees the node down.
NODE_PID=""
echo "ERROR: the node's qod-node exited (status $rc) while its wrapper was still running; see the node output above" >&2
exit 1
