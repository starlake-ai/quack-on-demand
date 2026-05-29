#!/usr/bin/env bash
#
# Cut a release locally, matching the starlake-core flow.
#
# What this does:
#   1. Preflight: clean working tree, on `main`, required env vars present.
#   2. `sbt 'release with-defaults'` - sbt-release plugin:
#        - strips -SNAPSHOT from version.sbt
#        - commits the version bump
#        - creates a git tag `v<version>`
#        - signs + uploads to Sonatype Central Portal (publishSigned)
#        - releases the staging bundle (sonatypeBundleRelease)
#        - bumps version.sbt to the next -SNAPSHOT
#        - commits the next-snapshot
#        - pushes both commits + the tag to origin
#   3. Optionally builds multi-arch Docker image + pushes to Docker Hub
#      tagged `:<version>`, `:<major>.<minor>`, `:<major>`, `:latest`.
#      Skip with NO_DOCKER=1.
#
# Required env (export before running, or use a `.envrc` style helper):
#   SONATYPE_USERNAME       Central Portal user-token name
#                           (https://central.sonatype.com/account)
#   SONATYPE_PASSWORD       Central Portal user-token secret
#   PGP_PASSPHRASE          passphrase for your GPG signing key
#
# Optional env:
#   NO_DOCKER=1             skip the Docker Hub multi-arch push
#   DOCKERHUB_USERNAME      docker login (defaults to your current docker config)
#   DOCKER_PASSWORD         personal access token
#   REGISTRY_IMAGE          override (default starlakeai/quack-on-demand)
#   RELEASE_VERSION         pin the release version (default = strip -SNAPSHOT)
#   NEXT_VERSION            pin the next-snapshot   (default = bump patch + -SNAPSHOT)
#
# One-time GPG setup (if you haven't already):
#   gpg --gen-key                                # use hayssam.saleh@starlake.ai
#   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
#   # `release with-defaults` will prompt gpg-agent for the passphrase
#   # unless PGP_PASSPHRASE is set in env.
#
# Usage:
#   ./scripts/release.sh                     # full release + docker push
#   NO_DOCKER=1 ./scripts/release.sh         # sonatype only
#   RELEASE_VERSION=0.2.0 NEXT_VERSION=0.3.0-SNAPSHOT ./scripts/release.sh

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

REGISTRY_IMAGE="${REGISTRY_IMAGE:-starlakeai/quack-on-demand}"
NO_DOCKER="${NO_DOCKER:-0}"

# ---- Preflight ----
if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: working tree is dirty. Commit or stash before releasing." >&2
  git status --short >&2
  exit 1
fi

branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$branch" != "main" ]]; then
  echo "WARN: releasing from '$branch', not 'main'. Continue? [y/N]" >&2
  read -r ans
  [[ "$ans" =~ ^[Yy]$ ]] || exit 1
fi

: "${SONATYPE_USERNAME:?SONATYPE_USERNAME is required - Central Portal user-token name}"
: "${SONATYPE_PASSWORD:?SONATYPE_PASSWORD is required - Central Portal user-token secret}"
: "${PGP_PASSPHRASE:?PGP_PASSPHRASE is required - GPG signing key passphrase}"

if ! command -v gpg >/dev/null 2>&1; then
  echo "ERROR: gpg not on PATH. Install GnuPG before releasing." >&2
  exit 1
fi
if ! gpg --list-secret-keys --keyid-format LONG 2>/dev/null | grep -q '^sec'; then
  echo "ERROR: no GPG secret key in keyring. See the header of this script for setup." >&2
  exit 1
fi

# Current snapshot version, for the docker tag pre-compute later.
current_version="$(grep -E '^ThisBuild / version' version.sbt | sed -E 's/.*"([^"]+)".*/\1/')"
default_release="${current_version%-SNAPSHOT}"
release_version="${RELEASE_VERSION:-$default_release}"

echo "==========================================================="
echo " quack-on-demand release"
echo "   current:        $current_version"
echo "   release as:     $release_version"
echo "   next:           ${NEXT_VERSION:-(sbt-release default = bump patch + -SNAPSHOT)}"
echo "   docker push:    $([[ "$NO_DOCKER" == "1" ]] && echo skipped || echo "yes -> $REGISTRY_IMAGE")"
echo "==========================================================="
echo "Proceed? [y/N]"
read -r confirm
[[ "$confirm" =~ ^[Yy]$ ]] || exit 1

# ---- sbt-release ----
# `with-defaults` accepts the prompts non-interactively. We still pass the
# explicit version overrides on the command line so PR-driven releases can
# pin them via env without touching version.sbt first.
sbt_args=(release with-defaults)
[[ -n "${RELEASE_VERSION:-}" ]] && sbt_args+=("release-version" "$RELEASE_VERSION")
[[ -n "${NEXT_VERSION:-}"    ]] && sbt_args+=("next-version"    "$NEXT_VERSION")

# pgpPassphrase is injected via -Dgpg... so sbt-pgp's signing step does
# not block on the gpg-agent prompt.
SBT_OPTS="${SBT_OPTS:--Xss8M -Xmx5g -XX:+UseG1GC}" \
sbt -no-colors \
  "set ThisBuild / pgpPassphrase := Some(\"$PGP_PASSPHRASE\".toCharArray)" \
  "${sbt_args[@]}"

# ---- Optional: multi-arch Docker push ----
if [[ "$NO_DOCKER" == "1" ]]; then
  echo "skipping Docker Hub push (NO_DOCKER=1)."
else
  if ! command -v docker >/dev/null 2>&1; then
    echo "WARN: docker not on PATH; skipping image push." >&2
  else
    minor="$(echo "$release_version" | cut -d. -f1-2)"
    major="$(echo "$release_version" | cut -d. -f1)"
    echo "building multi-arch image ($REGISTRY_IMAGE:$release_version + $minor + $major + latest)..."

    if [[ -n "${DOCKERHUB_USERNAME:-}" && -n "${DOCKER_PASSWORD:-}" ]]; then
      echo "$DOCKER_PASSWORD" | docker login -u "$DOCKERHUB_USERNAME" --password-stdin
    fi

    # Set up buildx (idempotent - `create --use` errors if it already exists).
    docker buildx create --use --name quack-release-builder >/dev/null 2>&1 || \
      docker buildx use quack-release-builder

    docker buildx build \
      --platform linux/amd64,linux/arm64 \
      --file Dockerfile \
      -t "$REGISTRY_IMAGE:$release_version" \
      -t "$REGISTRY_IMAGE:$minor" \
      -t "$REGISTRY_IMAGE:$major" \
      -t "$REGISTRY_IMAGE:latest" \
      --push \
      .
    echo "pushed: $REGISTRY_IMAGE:$release_version (+ $minor + $major + latest)"
  fi
fi

echo
echo "release complete."
echo "  - tag:    v$release_version (already pushed by sbt-release)"
echo "  - jar:    https://central.sonatype.com/artifact/ai.starlake/quack-on-demand_3/$release_version"
[[ "$NO_DOCKER" != "1" ]] && echo "  - image:  docker pull $REGISTRY_IMAGE:$release_version"