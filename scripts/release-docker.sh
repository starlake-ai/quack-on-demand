#!/usr/bin/env bash
#
# Release phase 3/3: build + push the multi-arch Docker image to Docker Hub.
#
# Independent of the other phases and safely re-runnable. Tags the image
# :<version>, :<major>.<minor>, :<major>, :latest.
#
# The image builds the assembly from source, so the jar version + libquackwire
# coord come from build.sbt / version.sbt in the build context. By the time
# this runs those are back on the next -SNAPSHOT (whose libquackwire isn't
# published), so we materialize the RELEASED build.sbt / version.sbt from the
# v<version> tag for the build, then restore the working tree (trap covers
# errors).
#
# Version resolution (first hit wins):
#   RELEASE_VERSION env  ->  first CLI arg  ->  latest v* git tag
#
# Optional env: REGISTRY_IMAGE (default starlakeai/quack-on-demand),
#               DOCKERHUB_USERNAME + DOCKER_PASSWORD (else uses your docker login).
#
# Usage:
#   ./scripts/release-docker.sh
#   ./scripts/release-docker.sh 0.3.5
#   RELEASE_VERSION=0.3.5 ./scripts/release-docker.sh

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-lib.sh"

require_cmd docker
docker buildx version >/dev/null 2>&1 || die "docker buildx not available."

release_version="${RELEASE_VERSION:-${1:-}}"
if [[ -z "$release_version" ]]; then
  release_version="$(git tag --sort=-v:refname --list 'v*' | head -1 | sed 's/^v//')"
fi
[[ -n "$release_version" ]] || die "could not resolve a release version (pass one as an arg or set RELEASE_VERSION)."
git rev-parse -q --verify "refs/tags/v${release_version}" >/dev/null \
  || die "tag v${release_version} does not exist. Run release-jar.sh first."

minor="$(echo "$release_version" | cut -d. -f1-2)"
major="$(echo "$release_version" | cut -d. -f1)"

echo "==========================================================="
echo " release Docker image"
echo "   version: $release_version"
echo "   image:   $REGISTRY_IMAGE:$release_version (+ $minor + $major + latest)"
echo "==========================================================="
confirm "Proceed?" || exit 1

# Materialize the released versions from the tag for the build, restore after.
git checkout "v${release_version}" -- build.sbt version.sbt
trap 'git checkout HEAD -- build.sbt version.sbt' EXIT

if [[ -n "${DOCKERHUB_USERNAME:-}" && -n "${DOCKER_PASSWORD:-}" ]]; then
  echo "$DOCKER_PASSWORD" | docker login -u "$DOCKERHUB_USERNAME" --password-stdin
fi

# Idempotent buildx builder setup.
docker buildx create --use --name quack-release-builder >/dev/null 2>&1 \
  || docker buildx use quack-release-builder

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --file Dockerfile \
  -t "$REGISTRY_IMAGE:$release_version" \
  -t "$REGISTRY_IMAGE:$minor" \
  -t "$REGISTRY_IMAGE:$major" \
  -t "$REGISTRY_IMAGE:latest" \
  --push \
  .

git checkout HEAD -- build.sbt version.sbt
trap - EXIT

echo
echo "pushed: $REGISTRY_IMAGE:$release_version (+ $minor + $major + latest)"
echo "  docker pull $REGISTRY_IMAGE:$release_version"