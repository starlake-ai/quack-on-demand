# Starflow (the Starlake API + UI) for the `starflow` profile of docker-compose.yml.
#
# Installs a Starflow release the same way `qod start --with-starflow` does:
# fetch starflow/distrib/starlake.sh and run `SL_VERSION=<v> starlake install`
# unattended, then run bin/api/bin/local-run-api. The published
# starlakeai/starlake-*-api images carry the API without the UI, hence this
# image.
#
#   docker compose --profile starflow build starflow

FROM eclipse-temurin:25-jre

ARG STARFLOW_VERSION=1.8.8
ARG STARLAKE_SH_URL=https://raw.githubusercontent.com/starlake-ai/starflow/master/distrib/starlake.sh

RUN set -e; apt-get update -y \
 && apt-get install -y --no-install-recommends bash curl git unzip tini ca-certificates \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# Starflow commits project changes with git: give it an identity.
RUN git config --system user.name "Starflow" \
 && git config --system user.email "starflow@localhost" \
 && git config --system init.defaultBranch main \
 && git config --system --add safe.directory '*'

WORKDIR /app/starlake
RUN set -e; curl -fsSL "$STARLAKE_SH_URL" -o starlake \
 && chmod +x starlake \
 && SL_VERSION="$STARFLOW_VERSION" ./starlake install \
 && test -f bin/api/bin/local-run-api \
 && chmod +x bin/api/bin/* bin/api/git/*.sh \
 && mkdir -p projects dags

ENV STARFLOW_VERSION=$STARFLOW_VERSION \
    SL_API_HTTP_INTERFACE=0.0.0.0 \
    SL_API_HTTP_PORT=9900

EXPOSE 9900

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/app/starlake/bin/api/bin/local-run-api", "/app/starlake"]
