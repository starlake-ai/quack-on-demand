# syntax=docker/dockerfile:1.7

# =========================================================================
#  Stage 1 — Build the uber-jar (UI + Scala)
# =========================================================================
FROM eclipse-temurin:17-jdk AS build

# sbt + node (the project's `sbt assembly` shells out to `npm run build` for
# the React admin console).
RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends curl gnupg ca-certificates apt-transport-https
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" \
  > /etc/apt/sources.list.d/sbt.list
curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99e82a75642ac823" \
  | gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get update
apt-get install -y --no-install-recommends sbt nodejs
rm -rf /var/lib/apt/lists/*
EOF

WORKDIR /src

# Pre-fetch deps using only the build descriptors — keeps the layer cached
# across source-only changes.
COPY build.sbt /src/
COPY project/  /src/project/
COPY ui/package.json ui/package-lock.json* /src/ui/
RUN --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2/cache \
    --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules \
    sbt -no-colors update && \
    (cd /src/ui && npm ci)

COPY . /src/
# UiBuild.scala skips `npm ci` when ui/node_modules already exists — and the
# cache-mount target above is always an existing (possibly empty) directory,
# so we re-run `npm ci` here to guarantee the tsc/vite binaries are present
# before `sbt assembly` invokes `npm run build`.
RUN --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2/cache \
    --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules \
    (cd /src/ui && npm ci) && \
    sbt -no-colors assembly && \
    cp distrib/quack-on-demand-assembly-*.jar /quack-on-demand.jar

# =========================================================================
#  Stage 2 — Runtime
# =========================================================================
FROM eclipse-temurin:17-jre

ARG DUCKDB_VERSION=1.5.3
ARG TARGETARCH

# Runtime deps:
#   duckdb              — required by spawn-quack-node.sh to host each Quack node
#   postgresql-client   — psql, bootstraps the catalog DB if missing
#   openssl             — auto-generates the self-signed TLS cert on first boot
#   bash + tini         — script interpreter + PID 1 signal forwarder
RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  bash tini curl ca-certificates unzip postgresql-client openssl

case "${TARGETARCH:-amd64}" in
  amd64) DUCKDB_ARCH=linux-amd64 ;;
  arm64) DUCKDB_ARCH=linux-arm64 ;;
  *)     echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
esac
curl -fsSL "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb_cli-${DUCKDB_ARCH}.zip" \
  -o /tmp/duckdb.zip
unzip -d /usr/local/bin /tmp/duckdb.zip
chmod +x /usr/local/bin/duckdb
rm /tmp/duckdb.zip

apt-get purge -y --auto-remove curl unzip
rm -rf /var/lib/apt/lists/*
EOF

# Non-root runtime user. Newer Ubuntu base images (24.04+) ship with an
# `ubuntu` user already at UID 1000 — remove it first so this useradd
# can claim the same UID.
RUN if id -u 1000 >/dev/null 2>&1; then userdel -r "$(id -un 1000)" 2>/dev/null || true; fi && \
    useradd --create-home --shell /bin/bash --uid 1000 quack

WORKDIR /app
COPY --from=build /quack-on-demand.jar           /app/quack-on-demand.jar
COPY scripts/spawn-quack-node.sh                 /app/scripts/spawn-quack-node.sh
RUN chmod +x /app/scripts/spawn-quack-node.sh && \
    mkdir -p /app/certs /app/state /app/ducklake && \
    chown -R quack:quack /app

USER quack

# Manager REST + UI (20900) and FlightSQL edge (31338).
# Local-mode Quack nodes lease ports from SL_QUACK_MIN_PORT..SL_QUACK_MAX_PORT;
# expose the default range so a host port-forward can reach them too.
EXPOSE 20900 31338 21900-22500

# Sensible container defaults. Override at run time via -e.
ENV SL_QUACK_ON_DEMAND_HOST=0.0.0.0 \
    PROXY_HOST=0.0.0.0 \
    SL_QUACK_DUCKLAKE_DATA_PATH=/app/ducklake \
    SL_QUACK_STATE_PATH=/app/state/quack-on-demand-state.json \
    PROXY_TLS_CERT_CHAIN=/app/certs/server-cert.pem \
    PROXY_TLS_PRIVATE_KEY=/app/certs/server-key.pem \
    JAVA_OPTS=""

# `-Darrow.allocation.manager.type=Unsafe` pins Arrow to arrow-memory-unsafe;
# without it Arrow picks netty and crashes with NoSuchFieldError on Java 17+.
# Add-Opens is set in the jar manifest, so no extra --add-opens flags here.
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-Darrow.allocation.manager.type=Unsafe", "-jar", "/app/quack-on-demand.jar"]