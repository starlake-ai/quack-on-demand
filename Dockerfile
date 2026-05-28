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
ARG TARGETARCH
RUN --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2/cache \
    --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules <<EOF
set -eu
cd /src/ui && npm ci
cd /src && sbt -no-colors assembly
cp distrib/quack-on-demand-assembly-*.jar /quack-on-demand.jar

# Strip non-Linux DuckDB JNI natives from the assembly jar (saves ~190MB).
# The jar bundles libduckdb_java.so_{osx_universal,linux_amd64,linux_arm64,
# windows_amd64} so it can run anywhere; only the matching Linux one is
# ever loaded inside the runtime container.
apt-get update
apt-get install -y --no-install-recommends zip
zip -dq /quack-on-demand.jar \
  libduckdb_java.so_osx_universal \
  libduckdb_java.so_windows_amd64
case "${TARGETARCH:-amd64}" in
  amd64) zip -dq /quack-on-demand.jar libduckdb_java.so_linux_arm64 ;;
  arm64) zip -dq /quack-on-demand.jar libduckdb_java.so_linux_amd64 ;;
esac
rm -rf /var/lib/apt/lists/*
EOF

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
# Use --chown on COPY rather than a trailing `chown -R`. `chown -R` rewrites
# every file into a new layer (~213MB doubled the assembly jar); --chown is
# applied as the file is staged, so no extra layer is produced. Same for
# --chmod on the scripts.
COPY --from=build --chown=quack:quack /quack-on-demand.jar           /app/quack-on-demand.jar
COPY --chown=quack:quack --chmod=0755 scripts/spawn-quack-node.sh    /app/scripts/spawn-quack-node.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-tpch-dbgen.sh     /app/scripts/load-tpch-dbgen.sh
RUN install -d -o quack -g quack /app/certs /app/state /app/ducklake

USER quack

# Manager REST + UI (20900) and FlightSQL edge (31338).
# Local-mode Quack nodes lease ports from SL_QUACK_MIN_PORT..SL_QUACK_MAX_PORT;
# expose the default range so a host port-forward can reach them too.
EXPOSE 20900 31338 21900-22500

# Sensible container defaults. Override at run time via -e.
ENV SL_QUACK_ON_DEMAND_HOST=0.0.0.0 \
    PROXY_HOST=0.0.0.0 \
    SL_QUACK_DUCKLAKE_DATA_PATH=/app/ducklake/tpch \
    SL_QUACK_STATE_PATH=/app/state/quack-on-demand-state.json \
    PROXY_TLS_CERT_CHAIN=/app/certs/server-cert.pem \
    PROXY_TLS_PRIVATE_KEY=/app/certs/server-key.pem \
    JAVA_OPTS=""

# `-Darrow.allocation.manager.type=Unsafe` pins Arrow to arrow-memory-unsafe;
# without it Arrow picks netty and crashes with NoSuchFieldError on Java 17+.
# Add-Opens is set in the jar manifest, so no extra --add-opens flags here.
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-Darrow.allocation.manager.type=Unsafe", "-jar", "/app/quack-on-demand.jar"]