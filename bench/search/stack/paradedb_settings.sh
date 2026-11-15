#!/usr/bin/env bash
# Applies paradedb_setup.sql to the running part 2 ParadeDB stack and restarts its container;
# the data volume is kept. Usage: paradedb_settings.sh <p2>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
docker ps --format '{{.Names}}' | grep -qx qod-p2-paradedb || { echo "qod-p2-paradedb is not running" >&2; exit 2; }
wait_ready() {
  for _ in $(seq 1 180); do
    if docker exec qod-p2-paradedb pg_isready -h 127.0.0.1 -U postgres > /dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "ParadeDB did not become ready" >&2; exit 1
}
docker exec -i -u postgres qod-p2-paradedb psql -q -v ON_ERROR_STOP=1 -f - < "$HERE/paradedb_setup.sql" 2>&1 | grep -v NOTICE || true
docker restart qod-p2-paradedb > /dev/null
wait_ready
docker exec -u postgres qod-p2-paradedb psql -Atc "SELECT name || '=' || setting FROM pg_settings WHERE name IN ('max_worker_processes','max_parallel_workers','max_parallel_workers_per_gather','max_parallel_maintenance_workers','shared_buffers','work_mem','maintenance_work_mem','effective_cache_size','ssl') ORDER BY 1"
echo "ParadeDB p2 settings applied"
