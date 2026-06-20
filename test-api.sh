#!/usr/bin/env bash
# End-to-end smoke against a running manager (port 20900). Uses the demo
# bootstrap tenants (acme + acme_tpch) so we don't fight with the running
# manager's pre-existing state -- the demo's `bi` / `etl` pools live alongside
# our temporary `smoke` pool.
#
# Run order:
#   ./scripts/run-jar.sh   # in another shell
#   ./test-api.sh
set -euo pipefail
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-20900}"
BASE="http://$HOST:$PORT/api"
TENANT="${TENANT:-acme}"
TENANT_DB="${TENANT_DB:-acme_tpch}"
POOL="${POOL:-smoke}"

echo "==> health"
curl -fsS "http://$HOST:$PORT/health"
echo

echo "==> ensure tenant '$TENANT' exists (idempotent: 409 is OK; demo bootstrap usually pre-creates it)"
# Tenants are first-class - pool creation requires the tenant to be registered.
# `-f` would fail the script on 409, so use a plain curl and tolerate that case.
HTTP=$(curl -s -o /tmp/test-api-tenant.out -w '%{http_code}' \
  -X POST "$BASE/tenant/create" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$TENANT\",\"displayName\":\"$TENANT\",\"metastore\":{}}") || true
if [ "$HTTP" != "200" ] && [ "$HTTP" != "409" ]; then
  echo "tenant/create returned HTTP $HTTP"; cat /tmp/test-api-tenant.out; exit 1
fi
echo

# Note: tenantDb 'acme_tpch' is pre-created by the demo bootstrap. We skip
# creating it -- if it's somehow missing the pool/create call below will
# fail loudly with a clear error.

echo "==> create pool $TENANT/$TENANT_DB/$POOL (size=2: 1 RO, 1 DUAL, maxConcurrentPerNode=4)"
# Idempotent: 409 (already exists) is OK so the smoke test can be re-run
# without first nuking the pool.
HTTP=$(curl -s -o /tmp/test-api-pool.out -w '%{http_code}' \
  -X POST "$BASE/pool/create" -H 'Content-Type: application/json' \
  -d "{
    \"tenant\":\"$TENANT\",\"tenantDb\":\"$TENANT_DB\",\"pool\":\"$POOL\",\"size\":2,
    \"roleDistribution\":{\"writeonly\":0,\"readonly\":1,\"dual\":1},
    \"metastore\":{},
    \"maxConcurrentPerNode\":4
  }") || true
if [ "$HTTP" != "200" ] && [ "$HTTP" != "409" ]; then
  echo "pool/create returned HTTP $HTTP"; cat /tmp/test-api-pool.out; exit 1
fi
echo

echo "==> list"
curl -fsS "$BASE/pool/list"
echo

echo "==> status"
curl -fsS "$BASE/pool/$TENANT/$TENANT_DB/$POOL/status"
echo

echo "==> bump one node's maxConcurrent"
NODE_ID=$(curl -fsS "$BASE/pool/$TENANT/$TENANT_DB/$POOL/status" | \
  python3 -c "import sys, json; print(json.load(sys.stdin)['nodes'][0]['nodeId'])")
curl -fsS -X POST "$BASE/node/setMaxConcurrent" -H 'Content-Type: application/json' -d "{
  \"tenant\":\"$TENANT\",\"tenantDb\":\"$TENANT_DB\",\"pool\":\"$POOL\",\"nodeId\":\"$NODE_ID\",\"max\":8
}"
echo

echo "==> scale to 3 (add 1 DUAL)"
curl -fsS -X POST "$BASE/pool/scale" -H 'Content-Type: application/json' -d "{
  \"tenant\":\"$TENANT\",\"tenantDb\":\"$TENANT_DB\",\"pool\":\"$POOL\",\"targetSize\":3,
  \"roleDistribution\":{\"writeonly\":0,\"readonly\":1,\"dual\":2},\"force\":false
}"
echo

echo "==> stop (force) -- cleans up the temporary smoke pool so the demo's bi/etl pools stay clean"
curl -fsS -X POST "$BASE/pool/stop" -H 'Content-Type: application/json' -d "{
  \"tenant\":\"$TENANT\",\"tenantDb\":\"$TENANT_DB\",\"pool\":\"$POOL\",\"force\":true
}"
echo
echo "OK"