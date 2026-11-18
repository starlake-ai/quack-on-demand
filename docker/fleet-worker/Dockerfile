# syntax=docker/dockerfile:1.7
#
# Fleet server image: one quack node per container, assigned by a manager running with
# QOD_RUNTIME_TYPE=fleet. Runs `qod fleet join` (heartbeats, runs the assigned node through the
# bundled spawn script, which starts the qod-node the qod wheel bundles). Contract (see
# entrypoint.sh):
#
#   required  QOD_MANAGER_URL, QOD_FLEET_JOIN_TOKEN, QOD_FLEET_NAME, QOD_FLEET_ADVERTISE_HOST
#   optional  QOD_FLEET_NODE_PORT (21900; publish the SAME number: -p 21901:21901),
#             QOD_FLEET_INSECURE=true (plain http:// manager URL), QOD_S3_* / QOD_AZURE_*, PG*
#
# The DuckDB CLI only serves the build: it pre-installs the node's extensions. The image ships the
# release libduckdb where qod looks for it (DUCKDB_CACHE_DIR), so nothing is downloaded at run time.
#
# Build: docker build -t starlakeai/quack-on-demand-worker:local -f docker/fleet-worker/Dockerfile .

# The qod wheel from this checkout, never a PyPI release that may lag it. Pure Python plus the
# bundled qod-node binaries, so the builder's interpreter version does not matter.
FROM python:3.11-slim-bookworm AS wheel
COPY cli/ /src/cli/
RUN pip wheel --no-deps --wheel-dir /wheels /src/cli

FROM debian:bookworm-slim AS duckdb

ARG TARGETARCH
ARG DUCKDB_VERSION=2.0.0

RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl
rm -rf /var/lib/apt/lists/*
EOF

# libduckdb in the cache layout qod reads (<DUCKDB_CACHE_DIR>/<version>/lib), and the node's
# extensions pre-installed so a node needs no outbound internet on first ATTACH.
RUN <<EOF
set -eu
case "${TARGETARCH:-$(dpkg --print-architecture)}" in
  amd64) ARCH=linux-amd64 ;;
  arm64) ARCH=linux-arm64 ;;
  *) echo "unsupported architecture" >&2; exit 1 ;;
esac
base="https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}"
mkdir -p "/opt/duckdb/${DUCKDB_VERSION}/lib" /tmp/libs
# The sha256 of each DuckDB release asset this image downloads, one line per <version>/<asset>.
# They must equal _DUCKDB_SHA256 in cli/src/qod_cli/launcher.py (scripts/release/release_manifest.py
# --check-pins refuses a difference), and a DUCKDB_VERSION with no line here fails the build.
duckdb_sha256() {
  case "$1" in
    2.0.0/duckdb-cli-linux-amd64.tar.gz) echo c6507206baf7793c6013dbd82368469a45f20dc0e17a6f92f204602f652ed2bc ;;
    2.0.0/duckdb-cli-linux-arm64.tar.gz) echo 859e1875d2a9515fbb4bf8871061437a14af82320da68c753f094c4b776348fe ;;
    2.0.0/duckdb-shared-libs-linux-amd64.tar.gz) echo 993ad89e7dfca66cd6da994c2cad090c2fc160ddf9f550498a098ab4bb13cfb1 ;;
    2.0.0/duckdb-shared-libs-linux-arm64.tar.gz) echo c60eecf91143ca6bbac55c4f4d40dc22520488b0ac257c86e0fe9a2b451cf2b7 ;;
  esac
}
# Downloads one release asset to a file and checks its sha256 before anything unpacks it.
fetch_duckdb() {
  digest="$(duckdb_sha256 "${DUCKDB_VERSION}/$1")"
  if [ -z "$digest" ]; then
    echo "no pinned sha256 for $1 of DuckDB ${DUCKDB_VERSION}: add it to duckdb_sha256 in this Dockerfile and to _DUCKDB_SHA256 in cli/src/qod_cli/launcher.py" >&2
    exit 1
  fi
  curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "$base/$1" -o "$2"
  echo "$digest  $2" | sha256sum -c -
}
fetch_duckdb "duckdb-cli-${ARCH}.tar.gz" /tmp/cli.tar.gz
fetch_duckdb "duckdb-shared-libs-${ARCH}.tar.gz" /tmp/libs.tar.gz
tar -xzf /tmp/cli.tar.gz -C /usr/local/bin duckdb
tar -xzf /tmp/libs.tar.gz -C /tmp/libs
cp /tmp/libs/libduckdb.so "/opt/duckdb/${DUCKDB_VERSION}/lib/"
mkdir -p /opt/duckdb-extensions
HOME=/opt/duckdb-extensions duckdb -c "INSTALL ducklake; INSTALL postgres; INSTALL quack; INSTALL httpfs; INSTALL aws;"
chmod -R a+rX /opt/duckdb /opt/duckdb-extensions
EOF

FROM debian:bookworm-slim

RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates bash postgresql-client tini python3 python3-venv
rm -rf /var/lib/apt/lists/*
EOF

COPY --from=duckdb /opt/duckdb /opt/duckdb
COPY --from=duckdb /opt/duckdb-extensions /opt/duckdb-extensions
ENV DUCKDB_CACHE_DIR=/opt/duckdb

COPY --from=wheel /wheels /tmp/wheels
RUN python3 -m venv /opt/qod \
 && /opt/qod/bin/pip install --no-cache-dir /tmp/wheels/*.whl \
 && rm -rf /tmp/wheels \
 && /opt/qod/bin/qod --help >/dev/null

COPY --chmod=0755 docker/fleet-worker/entrypoint.sh /usr/local/bin/fleet-worker-entrypoint

RUN useradd --create-home --shell /bin/bash --uid 1000 quack \
 && chown -R quack:quack /opt/duckdb-extensions
ENV HOME=/home/quack \
    PATH=/opt/qod/bin:$PATH
USER quack
WORKDIR /home/quack
RUN ln -sfn /opt/duckdb-extensions/.duckdb /home/quack/.duckdb

EXPOSE 21900

# tini forwards docker stop's SIGTERM to qod fleet join, which stops its node before exiting.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/fleet-worker-entrypoint"]
