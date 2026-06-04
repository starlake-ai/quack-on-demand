#!/usr/bin/env bash
set -euo pipefail
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-20900}"
BASE="http://$HOST:$PORT/api"

echo "==> health"
curl -fsS "http://$HOST:$PORT/health"
echo

echo "==> ensure tenant tpch exists (idempotent: 409 is OK)"
# Tenants are first-class - pool creation requires the tenant to be registered.
# `-f` would fail the script on 409, so use a plain curl and tolerate that case.
HTTP=$(curl -s -o /tmp/test-api-tenant.out -w '%{http_code}' \
  -X POST "$BASE/tenant/create" -H 'Content-Type: application/json' \
  -d '{"name":"tpch","metastore":{}}') || true
if [ "$HTTP" != "200" ] && [ "$HTTP" != "409" ]; then
  echo "tenant/create returned HTTP $HTTP"; cat /tmp/test-api-tenant.out; exit 1
fi
echo

echo "==> create pool tpch/sales (size=2: 1 RO, 1 DUAL, maxConcurrentPerNode=4)"
curl -fsS -X POST "$BASE/pool/create" -H 'Content-Type: application/json' -d '{
  "tenant":"tpch","pool":"sales","size":2,
  "roleDistribution":{"writeonly":0,"readonly":1,"dual":1},
  "metastore":{"pgHost":"localhost"},
  "maxConcurrentPerNode":4
}'
echo

echo "==> list"
curl -fsS "$BASE/pool/list"
echo

echo "==> status"
curl -fsS "$BASE/pool/tpch/sales/status"
echo

echo "==> bump one node's maxConcurrent"
NODE_ID=$(curl -fsS "$BASE/pool/tpch/sales/status" | python3 -c "import sys, json; print(json.load(sys.stdin)['nodes'][0]['nodeId'])")
curl -fsS -X POST "$BASE/node/setMaxConcurrent" -H 'Content-Type: application/json' -d "{
  \"tenant\":\"tpch\",\"pool\":\"sales\",\"nodeId\":\"$NODE_ID\",\"max\":8
}"
echo

echo "==> scale to 3 (add 1 DUAL)"
curl -fsS -X POST "$BASE/pool/scale" -H 'Content-Type: application/json' -d '{
  "tenant":"tpch","pool":"sales","targetSize":3,
  "roleDistribution":{"writeonly":0,"readonly":1,"dual":2},"force":false
}'
echo

echo "==> stop (force)"
curl -fsS -X POST "$BASE/pool/stop" -H 'Content-Type: application/json' -d '{
  "tenant":"tpch","pool":"sales","force":true
}'
echo
echo "OK"