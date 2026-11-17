# syntax=docker/dockerfile:1.7
#
# Image for one Quack node pod (the data plane that the manager spawns via
# KubernetesQuackBackend). Runs the project's spawn-quack-node.sh, the same boot script
# LocalQuackBackend uses, which starts qod-node (native/qod-node: the vendored binary of this
# checkout, linked against the release libduckdb installed beside it). Env-var contract:
#
#   QOD_NODE_PORT            - set by the manager's pod template
#   QOD_SECRETS_DIR          - read-only Secret volume: token, pgPassword, encryptionKey,
#                              federation / object-store SQL, object-store credentials
#                              (one file per key; never env vars)
#   pgHost / pgPort / pgUser / dbName / schemaName / dataPath
#                            - merged from defaultMetastore + spec.metastore
#   QOD_S3_ENDPOINT / _REGION / _URL_STYLE / _USE_SSL - optional object-store settings
#   HTTP_PROXY / HTTPS_PROXY           - optional egress proxy
#
# The DuckDB CLI only serves the build: it pre-installs the node's extensions. The image ships
# libduckdb and qod-node, no CLI.
#
# Build: docker build -t starlakeai/quack-on-demand-node:local -f docker/quack-node/Dockerfile .

FROM debian:bookworm-slim AS duckdb

ARG TARGETARCH
ARG DUCKDB_VERSION=2.0.0
# The node protocol of this build (QOD_NODE_PROTOCOL in native/qod-node/CMakeLists.txt, which
# QodNodeBinarySpec pins): a vendored qod-node of another protocol fails the build. Pods never pass
# through the manager's QodNodeBinary check, so this is theirs.
ARG QOD_NODE_PROTOCOL=6
# Whether the vendored qod-node links qod_search_core (QOD_NODE_SEARCH in native/qod-node/CMakeLists.txt): OFF
# while search Phase 1 stays unmerged (OWNER RULING 2026-11-16, D2). A binary of the other setting fails the
# build, and one that links it ships its third-party notices as /opt/qod-node/THIRD-PARTY-NOTICES-qod-search.txt.
ARG QOD_NODE_SEARCH=OFF

RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl
rm -rf /var/lib/apt/lists/*
EOF

# The release CLI (build only) and shared library (linux-amd64 / linux-arm64), and the vendored
# qod-node of the target architecture as /opt/qod-node/bin/qod-node: its rpath finds libduckdb in
# /opt/qod-node/lib.
COPY native/qod-node/binaries/ /tmp/qod-node-binaries/
RUN <<EOF
set -eu
case "${TARGETARCH:-$(dpkg --print-architecture)}" in
  amd64) ARCH=linux-amd64; PLATFORM=linux-x86_64 ;;
  arm64) ARCH=linux-arm64; PLATFORM=linux-aarch64 ;;
  *) echo "unsupported architecture" >&2; exit 1 ;;
esac
base="https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}"
mkdir -p /opt/qod-node/bin /opt/qod-node/lib /tmp/libs
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "$base/duckdb-cli-${ARCH}.tar.gz" -o /tmp/cli.tar.gz
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "$base/duckdb-shared-libs-${ARCH}.tar.gz" -o /tmp/libs.tar.gz
tar -xzf /tmp/cli.tar.gz -C /usr/local/bin duckdb
tar -xzf /tmp/libs.tar.gz -C /tmp/libs
cp /tmp/libs/libduckdb.so /opt/qod-node/lib/
test -f "/tmp/qod-node-binaries/$PLATFORM/qod-node" \
  || { echo "no vendored qod-node for $PLATFORM: run scripts/release/refresh-qod-node-binaries.sh" >&2; exit 1; }
install -m 0755 "/tmp/qod-node-binaries/$PLATFORM/qod-node" /opt/qod-node/bin/qod-node
# The binary must start against this libduckdb, and be built for it and for this node protocol.
/opt/qod-node/bin/qod-node --build-info
/opt/qod-node/bin/qod-node --build-info | grep -q "qod-node-build-info:protocol=${QOD_NODE_PROTOCOL};duckdb=v${DUCKDB_VERSION};" \
  || { echo "the vendored qod-node is not node protocol ${QOD_NODE_PROTOCOL}: run scripts/release/refresh-qod-node-binaries.sh" >&2; exit 1; }
# A binary from before the search flag prints none, and links no search library.
case "$(/opt/qod-node/bin/qod-node --build-info | head -n1)" in
  *" search=on") SEARCH=ON ;;
  *) SEARCH=OFF ;;
esac
[ "$SEARCH" = "${QOD_NODE_SEARCH}" ] \
  || { echo "the vendored qod-node is built with QOD_NODE_SEARCH=$SEARCH; this image takes ${QOD_NODE_SEARCH}" >&2; exit 1; }
if [ "$SEARCH" = ON ]; then
  test -f /tmp/qod-node-binaries/THIRD-PARTY-NOTICES-qod-search.txt \
    || { echo "the vendored qod-node links qod_search_core and its notices are not beside it: run scripts/release/refresh-qod-node-binaries.sh" >&2; exit 1; }
  install -m 0644 /tmp/qod-node-binaries/THIRD-PARTY-NOTICES-qod-search.txt /opt/qod-node/THIRD-PARTY-NOTICES-qod-search.txt
fi
# Pre-install the runtime extensions so the pod needs no outbound internet on first ATTACH.
mkdir -p /opt/duckdb-extensions
HOME=/opt/duckdb-extensions duckdb -c "INSTALL ducklake; INSTALL postgres; INSTALL quack; INSTALL httpfs; INSTALL aws;"
chmod -R a+rX /opt/duckdb-extensions
EOF

FROM debian:bookworm-slim

RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates bash postgresql-client tini
rm -rf /var/lib/apt/lists/*
EOF

COPY --from=duckdb /opt/qod-node /opt/qod-node
COPY --from=duckdb /opt/duckdb-extensions /opt/duckdb-extensions
ENV QOD_NODE_BIN=/opt/qod-node/bin/qod-node

# The local spawn script is the single source of truth across local + K8s.
COPY scripts/spawn-quack-node.sh           /app/scripts/spawn-quack-node.sh
COPY docker/quack-node/entrypoint.sh       /usr/local/bin/quack-entrypoint
RUN chmod +x /app/scripts/spawn-quack-node.sh /usr/local/bin/quack-entrypoint

# Non-root with a writable HOME for DuckDB's extension cache. Re-use the
# pre-installed extension directory. Pre-create the default data path
# (matches values.yaml storage.dataPath = /var/quack/ducklake) so the
# spawn script's `mkdir -p` doesn't hit Permission denied at boot.
RUN useradd --create-home --shell /bin/bash --uid 1000 quack \
 && chown -R quack:quack /opt/duckdb-extensions \
 && install -d -o quack -g quack -m 0755 /var/quack /var/quack/ducklake
ENV HOME=/home/quack
USER quack
WORKDIR /home/quack
# Symlink the pre-installed extensions into the per-user .duckdb so qod-node
# finds them without re-downloading.
RUN ln -sfn /opt/duckdb-extensions/.duckdb /home/quack/.duckdb || true

EXPOSE 8080

# tini reaps zombie children + propagates SIGTERM cleanly to the spawn script, which stops
# qod-node.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/quack-entrypoint"]
