#!/usr/bin/env bash
# Writes the part 2 DuckLake DSN and data path files (mode 600) from the running QoD stack:
#   <p2>/secrets/ducklake.dsn    ducklake:postgres:host=127.0.0.1 port=27499 dbname=acme_lake user=postgres password=postgres
#   <p2>/secrets/data_path.txt   the catalog's data_path, read as the superuser
# The embedded Postgres is zonky's, whose superuser postgres authenticates by trust on
# loopback (EmbeddedControlPlane.scala: "the password is not verified"), so its password is
# the placeholder postgres. Prints neither file. Usage: write_dsn.sh <p2>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
P2="$1"
umask 077
printf 'ducklake:postgres:host=127.0.0.1 port=27499 dbname=acme_lake user=postgres password=postgres\n' \
  > "$P2/secrets/ducklake.dsn"
"$HERE/qod_sql.sh" "$P2" --csv "SELECT value FROM __ducklake_metadata_acme_lake.ducklake_metadata WHERE key = 'data_path'" \
  | tail -n +2 | tr -d '"\r' > "$P2/secrets/data_path.txt"
chmod 600 "$P2/secrets/ducklake.dsn" "$P2/secrets/data_path.txt"
# qod sql --csv ends its lines with CRLF: the CR is stripped above; the path must end in '/'.
[ "$(wc -l < "$P2/secrets/data_path.txt" | tr -d ' ')" = 1 ] && [ "$(tail -c 2 "$P2/secrets/data_path.txt" | head -c 1)" = / ] \
  || { echo "data_path not read as one line ending in /" >&2; exit 1; }
echo "wrote $P2/secrets/ducklake.dsn and $P2/secrets/data_path.txt"
