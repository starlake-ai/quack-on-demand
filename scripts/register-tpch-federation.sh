#!/usr/bin/env bash
# Register a memory-backed tenant-db plus a federated Postgres source on
# the running quack-on-demand manager. Invoked from the entry scripts
# (run-docker-compose.sh, run-jar.sh, run-local-stack-k8s.sh) right after
# LOAD_TPCH succeeds. Idempotent: re-runs are safe; the manager treats
# duplicates as upserts where it can and 409s otherwise (which the script
# logs and ignores so a re-run does not abort the launch).
#
# Why a SECOND tenant-db? The TPC-H seed targets the existing DuckLake
# tenant-db (default `tpch_tpch1`). The new tenant-db (default
# `tpch_fed`) has `kind=memory` so it owns no default catalog at all --
# its sole purpose is to demonstrate federation: a query lands on a
# memory node, the registered `setupSql` ATTACHes the seed's Postgres
# database under the alias `tpch_pg`, and queries against
# `tpch_pg.public.*` go straight to Postgres.
#
# Env:
#   MANAGER_URL       Default: http://localhost:20900
#   MANAGER_API_KEY   Default: unset (sends no X-API-Key header)
#   WAIT_TIMEOUT_SEC  Default: 120 (max seconds to wait for /health)
#   BS_TENANT         Default: tpch       (the existing tenant)
#   FED_TENANTDB      Default: fed        (memory tenant-db suffix; full name = ${BS_TENANT}_${FED_TENANTDB})
#   FED_ALIAS         Default: tpch_pg    (federation catalog alias)
#   PG_HOST           REQUIRED            (Postgres host reachable from quack nodes)
#   PG_PORT           Default: 5432
#   PG_USER           Default: postgres
#   PG_PASS           REQUIRED
#   TARGET_DB         REQUIRED            (Postgres database to federate, e.g. tpch_tpch1)

set -euo pipefail

MANAGER_URL="${MANAGER_URL:-http://localhost:20900}"
WAIT_TIMEOUT_SEC="${WAIT_TIMEOUT_SEC:-120}"
BS_TENANT="${BS_TENANT:-tpch}"
FED_TENANTDB="${FED_TENANTDB:-fed}"
FED_ALIAS="${FED_ALIAS:-tpch_pg}"
PG_HOST="${PG_HOST:?PG_HOST is required}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:?PG_PASS is required}"
TARGET_DB="${TARGET_DB:?TARGET_DB is required (Postgres database to federate)}"

# Curl wrapper that swallows the API-key header when unset (avoids
# sending an empty value, which some auth layers reject).
hdr_args=()
if [[ -n "${MANAGER_API_KEY:-}" ]]; then
  hdr_args+=(-H "X-API-Key: ${MANAGER_API_KEY}")
fi

log() { echo "register-tpch-federation: $*"; }

log "config:"
log "  MANAGER_URL=$MANAGER_URL"
log "  BS_TENANT=$BS_TENANT  FED_TENANTDB=$FED_TENANTDB  FED_ALIAS=$FED_ALIAS"
log "  PG_HOST=$PG_HOST  PG_PORT=$PG_PORT  PG_USER=$PG_USER  PG_PASS=*** ($(echo -n "$PG_PASS" | wc -c | tr -d ' ') chars)"
log "  TARGET_DB=$TARGET_DB"
log "  WAIT_TIMEOUT_SEC=$WAIT_TIMEOUT_SEC"

# Wait until the manager responds. /health is the bare-path liveness
# endpoint mounted at the top of the routing tree by ManagerServer
# (see Endpoints.scala -> val health). Some deployments only expose
# /api/* through an ingress, so probe /api/health as a fallback. Any
# 2xx response on either path means the manager is up.
wait_for_manager() {
  local deadline=$(( SECONDS + WAIT_TIMEOUT_SEC ))
  log "waiting for manager (probes: /health, /api/health, timeout=${WAIT_TIMEOUT_SEC}s)"
  local last_code=""
  while (( SECONDS < deadline )); do
    for path in /health /api/health; do
      local code
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 \
        "${MANAGER_URL%/}${path}" 2>/dev/null || true)
      if [[ "$code" =~ ^2 ]]; then
        log "manager up (HTTP $code at $path)"
        return 0
      fi
      last_code="$code on $path"
    done
    sleep 2
  done
  log "timed out waiting for manager (last seen: $last_code)"
  return 1
}

# POST helper. Returns 0 on success OR 409 Conflict (already exists),
# returns 1 on any other failure with the response body printed.
post_or_conflict() {
  local path="$1" body="$2" label="$3"
  local tmp
  tmp="$(mktemp)"
  local code
  code=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -X POST "${MANAGER_URL%/}${path}" \
    -H 'Content-Type: application/json' \
    "${hdr_args[@]}" \
    -d "$body" || true)
  if [[ "$code" == 2?? ]]; then
    log "$label: ok ($code)"
    rm -f "$tmp"; return 0
  elif [[ "$code" == "409" ]]; then
    log "$label: already exists ($code), continuing"
    rm -f "$tmp"; return 0
  else
    log "$label: FAILED ($code)"
    sed 's/^/  > /' "$tmp" >&2 || true
    rm -f "$tmp"; return 1
  fi
}

# PUT helper (used for upsert-secret). 2xx is success.
put_or_warn() {
  local path="$1" body="$2" label="$3"
  local tmp
  tmp="$(mktemp)"
  local code
  code=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -X PUT "${MANAGER_URL%/}${path}" \
    -H 'Content-Type: application/json' \
    "${hdr_args[@]}" \
    -d "$body" || true)
  if [[ "$code" == 2?? ]]; then
    log "$label: ok ($code)"
    rm -f "$tmp"; return 0
  else
    log "$label: FAILED ($code)"
    sed 's/^/  > /' "$tmp" >&2 || true
    rm -f "$tmp"; return 1
  fi
}

wait_for_manager

# Compose the full memory tenant-db name the same way the manager
# normalizes it: if the suffix already starts with "${tenant}_", trust
# it; otherwise prepend "${tenant}_".
if [[ "$FED_TENANTDB" == "${BS_TENANT}_"* ]]; then
  fed_db_full="$FED_TENANTDB"
else
  fed_db_full="${BS_TENANT}_${FED_TENANTDB}"
fi

# Step 1: create the memory tenant-db. `defaultDatabase` is set to the
# federated alias so unqualified `FROM foo` lands on the federation by
# default; without this the `memory` catalog is empty.
read -r -d '' tdb_body <<EOF || true
{
  "tenant":          "${BS_TENANT}",
  "name":            "${FED_TENANTDB}",
  "kind":            "memory",
  "metastore":       {},
  "dataPath":        "",
  "objectStore":     {},
  "defaultDatabase": "${FED_ALIAS}",
  "defaultSchema":   "public"
}
EOF
post_or_conflict "/api/database/create" "$tdb_body" "create tenant-db '${fed_db_full}' (kind=memory)" || exit 1

# Step 2: register the federated source. The setupSql uses {{alias}} and
# {{secret.PG_PWD}} placeholders so the manager resolves them at node
# spawn -- the password never lands in the control-plane row's setupSql
# column as plaintext, only in qodstate_federated_secret.value.
read -r -d '' src_body <<EOF || true
{
  "alias":       "${FED_ALIAS}",
  "description": "Federated read into the seeded TPC-H Postgres database (${TARGET_DB}). Demo for kind=memory + federation.",
  "setupSql":    "INSTALL postgres; LOAD postgres;\nCREATE OR REPLACE SECRET {{alias}}_sec (\n  TYPE POSTGRES,\n  HOST '${PG_HOST}',\n  PORT ${PG_PORT},\n  DATABASE '${TARGET_DB}',\n  USER '${PG_USER}',\n  PASSWORD '{{secret.PG_PWD}}'\n);\nATTACH '' AS {{alias}} (TYPE POSTGRES, SECRET {{alias}}_sec, READ_ONLY);"
}
EOF
post_or_conflict \
  "/api/tenants/${BS_TENANT}/tenant-dbs/${fed_db_full}/federated-sources" \
  "$src_body" \
  "register federated source '${FED_ALIAS}' on ${fed_db_full}" || exit 1

# Step 3: upsert the secret. PUT is idempotent at the handler level.
read -r -d '' sec_body <<EOF || true
{
  "name":  "PG_PWD",
  "value": "${PG_PASS}"
}
EOF
put_or_warn \
  "/api/tenants/${BS_TENANT}/tenant-dbs/${fed_db_full}/federated-sources/${FED_ALIAS}/secrets" \
  "$sec_body" \
  "upsert secret PG_PWD" || exit 1

log "done. Try this from FlightSQL:"
log "  USE ${fed_db_full};                                    -- routes to the memory tenant-db"
log "  SELECT * FROM ${FED_ALIAS}.public.__ducklake_metadata; -- queries the federated Postgres"