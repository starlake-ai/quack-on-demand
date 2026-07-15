#!/usr/bin/env bash
#
# Cut a full release by running the three phase scripts in order:
#   1. release-libquackwire.sh  - native libquackwire jars -> Maven Central
#   2. release-jar.sh           - manager jar -> Maven Central, tag, GH release
#   3. release-docker.sh        - multi-arch image -> Docker Hub  (skip: NO_DOCKER=1)
#
# Each phase is individually re-runnable and idempotent, so if this stops
# partway (e.g. a network blip during a publish) you resume by running the
# failed phase directly - you don't have to restart from the top. See each
# script's header for details.
#
# Required env: SONATYPE_USERNAME, SONATYPE_PASSWORD, PGP_PASSPHRASE, PIPY_TOKEN.
# Optional env: NO_DOCKER=1, RELEASE_VERSION, NEXT_VERSION, REGISTRY_IMAGE,
#               DOCKERHUB_USERNAME, DOCKER_PASSWORD.
#
# Usage:
#   ./scripts/release.sh                 # full release + docker push
#   NO_DOCKER=1 ./scripts/release.sh     # sonatype + tag only
#   RELEASE_VERSION=0.2.0 NEXT_VERSION=0.3.0-SNAPSHOT ./scripts/release.sh

set -euo pipefail
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPTS/release-lib.sh"

require_pypi_creds

NO_DOCKER="${NO_DOCKER:-0}"
current="$(manager_version)"
release_version="${RELEASE_VERSION:-$(strip_snapshot "$current")}"

echo "==========================================================="
echo " quack-on-demand full release"
echo "   manager:      $current -> $release_version"
echo "   libquackwire: $(libquackwire_version)"
echo "   docker push:  $([[ "$NO_DOCKER" == "1" ]] && echo skipped || echo "yes -> $REGISTRY_IMAGE")"
echo "==========================================================="
echo "Runs 3 phases; each re-runnable on failure. Proceed? [y/N]"
read -r confirm
[[ "$confirm" =~ ^[Yy]$ ]] || exit 1

# Confirmed once here; the phases don't re-prompt.
export RELEASE_YES=1

"$SCRIPTS/release-libquackwire.sh"
"$SCRIPTS/release-jar.sh"
if [[ "$NO_DOCKER" == "1" ]]; then
  echo "skipping Docker Hub push (NO_DOCKER=1)."
else
  "$SCRIPTS/release-docker.sh"
fi

echo
echo "release complete."
echo "  - tag:   v${release_version} (pushed)"
echo "  - jar:   https://central.sonatype.com/artifact/ai.starlake/quack-on-demand_3/${release_version}"
[[ "$NO_DOCKER" != "1" ]] && echo "  - image: docker pull $REGISTRY_IMAGE:${release_version}"