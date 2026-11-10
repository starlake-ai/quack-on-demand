# syntax=docker/dockerfile:1.7

# =========================================================================
#  Stage 1 - Build the uber-jar (UI + Scala)
# =========================================================================
FROM eclipse-temurin:21-jdk AS build

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

# Pre-fetch deps using only the build descriptors - keeps the layer cached
# across source-only changes.
# The node_modules cache is keyed per TARGETARCH: a multi-platform buildx run
# executes the amd64 and arm64 stages concurrently, and `npm ci` starts by
# wiping node_modules - two builds sharing one cache mount race into
# ENOTEMPTY. The contents are arch-specific anyway (esbuild/rollup natives).
ARG TARGETARCH
COPY build.sbt /src/
COPY project/  /src/project/
COPY ui/package.json ui/package-lock.json* /src/ui/
RUN --mount=type=cache,sharing=locked,target=/root/.sbt \
    --mount=type=cache,sharing=locked,target=/root/.ivy2/cache \
    --mount=type=cache,sharing=locked,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules,id=ui-node-modules-$TARGETARCH \
    sbt -no-colors update && \
    (cd /src/ui && npm ci)

COPY . /src/
# UiBuild.scala skips `npm ci` when ui/node_modules already exists - and the
# cache-mount target above is always an existing (possibly empty) directory,
# so we re-run `npm ci` here to guarantee the tsc/vite binaries are present
# before `sbt assembly` invokes `npm run build`.
RUN --mount=type=cache,sharing=locked,target=/root/.sbt \
    --mount=type=cache,sharing=locked,target=/root/.ivy2/cache \
    --mount=type=cache,sharing=locked,target=/root/.cache/coursier \
    --mount=type=cache,target=/src/ui/node_modules,id=ui-node-modules-$TARGETARCH <<EOF
set -eu
cd /src/ui && npm ci
cd /src && sbt -no-colors assembly
cp distrib/quack-on-demand-assembly-*.jar /quack-on-demand.jar

# Strip the DuckDB JNI natives this image never loads from the assembly jar
# (saves ~170MB): the jar bundles one per platform so it can run anywhere
# (2.0.0.0: libduckdb_java.so_{linux_amd64,linux_arm64,macos_arm64,
# windows_amd64}; the set and its names change between DuckDB releases, so
# they are listed from the jar rather than named here), and only the
# matching Linux one is loaded inside the runtime container.
apt-get update
apt-get install -y --no-install-recommends zip unzip
case "${TARGETARCH:-amd64}" in
  amd64) keep=libduckdb_java.so_linux_amd64 ;;
  arm64) keep=libduckdb_java.so_linux_arm64 ;;
  *)     echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
esac
unzip -Z1 /quack-on-demand.jar | grep -qx "$keep" \
  || { echo "the DuckDB JDBC in the jar has no $keep" >&2; exit 1; }
drop="$(unzip -Z1 /quack-on-demand.jar | grep '^libduckdb_java\.so_' | grep -vx "$keep" || true)"
if [ -n "$drop" ]; then
  # shellcheck disable=SC2086  # one entry name per word
  zip -dq /quack-on-demand.jar $drop
fi
rm -rf /var/lib/apt/lists/*
EOF

# =========================================================================
#  Stage 2 - Runtime
# =========================================================================
FROM eclipse-temurin:21-jre

ARG DUCKDB_VERSION=2.0.0
ARG TARGETARCH

# Runtime deps:
#   duckdb              - required by spawn-quack-node.sh to host each Quack node
#   postgresql-client   - psql, bootstraps the catalog DB if missing
#   openssl             - auto-generates the self-signed TLS cert on first boot
#   bash + tini         - script interpreter + PID 1 signal forwarder
RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  bash tini curl ca-certificates postgresql-client openssl

case "${TARGETARCH:-amd64}" in
  amd64) DUCKDB_ARCH=linux-amd64 ;;
  arm64) DUCKDB_ARCH=linux-arm64 ;;
  *)     echo "Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
esac
# DuckDB 2.x release assets are flat .tar.gz archives (duckdb-cli-*, duckdb-shared-libs-*).
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb-cli-${DUCKDB_ARCH}.tar.gz" \
  -o /tmp/duckdb-cli.tar.gz
tar -xzf /tmp/duckdb-cli.tar.gz -C /usr/local/bin duckdb
chmod +x /usr/local/bin/duckdb
rm /tmp/duckdb-cli.tar.gz

# libduckdb.so is required at runtime by libquackwire (the JNI shim that is
# the manager's only client to its nodes), which links the pinned libduckdb
# dynamically (DuckDB::LibraryVersion / ArrowConverter / etc.). The duckdb
# CLI above is self-contained and does not provide a system-installed
# libduckdb.so.
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb-shared-libs-${DUCKDB_ARCH}.tar.gz" \
  -o /tmp/libduckdb.tar.gz
mkdir -p /tmp/libduckdb
tar -xzf /tmp/libduckdb.tar.gz -C /tmp/libduckdb
install -m 0755 /tmp/libduckdb/libduckdb.so /usr/local/lib/libduckdb.so
ldconfig
rm -rf /tmp/libduckdb.tar.gz /tmp/libduckdb

apt-get purge -y --auto-remove curl
rm -rf /var/lib/apt/lists/*
EOF

# Non-root runtime user. Newer Ubuntu base images (24.04+) ship with an
# `ubuntu` user already at UID 1000 - remove it first so this useradd
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
COPY --chown=quack:quack --chmod=0755 scripts/_load-common.sh        /app/scripts/_load-common.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-tpch-dbgen.sh     /app/scripts/load-tpch-dbgen.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-tpcds-dbgen.sh    /app/scripts/load-tpcds-dbgen.sh
COPY --chown=quack:quack --chmod=0755 scripts/load-ssb-dbgen.sh      /app/scripts/load-ssb-dbgen.sh
RUN install -d -o quack -g quack /app/certs /app/state /app/ducklake

USER quack

# Manager REST + UI (20900), the node verdict listener (20901), FlightSQL edge
# (31338), the native Quack protocol front door (9494, what a DuckDB client
# ATTACHes to) and the read-only REST data edge (31339, off unless
# QOD_REST_ENABLED=true).
# Local-mode Quack nodes lease ports from QOD_MIN_PORT..QOD_MAX_PORT;
# expose the default range so a host port-forward can reach them too.
EXPOSE 20900 20901 31338 9494 31339 21900-22500

# Sensible container defaults. Override at run time via -e.
ENV QOD_ON_DEMAND_HOST=0.0.0.0 \
    PROXY_HOST=0.0.0.0 \
    QOD_DUCKLAKE_DATA_PATH=/app/ducklake/tpch \
    QOD_STATE_PATH=/app/state/quack-on-demand-state.json \
    PROXY_TLS_CERT_CHAIN=/app/certs/server-cert.pem \
    PROXY_TLS_PRIVATE_KEY=/app/certs/server-key.pem \
    JAVA_OPTS=""

# `-Darrow.allocation.manager.type=Unsafe` pins Arrow to arrow-memory-unsafe;
# without it Arrow picks netty and crashes with NoSuchFieldError: chunkSize.
# Add-Opens is set in the jar manifest (Java 17 and later block the reflection
# Arrow's unsafe allocator needs unless it is opened), so no extra --add-opens
# flags here. The manager needs JDK 21 or later; this image has it.
# sh expands JAVA_OPTS; exec keeps the JVM as tini's child, so it gets the signals.
ENTRYPOINT ["/usr/bin/tini", "--", "sh", "-c", "exec java -Darrow.allocation.manager.type=Unsafe $JAVA_OPTS -jar /app/quack-on-demand.jar \"$@\"", "--"]