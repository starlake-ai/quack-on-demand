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
if ! command -v cmake >/dev/null 2>&1; then
  echo "ERROR: cmake not on PATH (needed for the libquackwire host build)." >&2
  exit 1
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "ERROR: gh CLI not on PATH (needed to download the non-host libquackwire binaries from CI)." >&2
  exit 1
fi
gh auth status >/dev/null 2>&1 || {
  echo "ERROR: 'gh auth login' first - we need to download artifacts from this repo's quackwire.yml runs." >&2
  exit 1
}

# Current snapshot version, for the docker tag pre-compute later.
current_version="$(grep -E '^ThisBuild / version' version.sbt | sed -E 's/.*"([^"]+)".*/\1/')"
default_release="${current_version%-SNAPSHOT}"
release_version="${RELEASE_VERSION:-$default_release}"

# libquackwire version: stripped of -SNAPSHOT for the release publish, then
# bumped rev+1 + -SNAPSHOT for the next dev cycle. See the comment block in
# build.sbt for the full version format.
libq_current="$(grep -E '^val libquackwireVersion' build.sbt | sed -E 's/.*"([^"]+)".*/\1/')"
case "$libq_current" in
  *-SNAPSHOT)
    libq_release="${libq_current%-SNAPSHOT}"
    # rev = last hyphen-separated segment of the non-snapshot version.
    libq_rev="${libq_release##*-}"
    libq_prefix="${libq_release%-*}"
    libq_next_rev=$((libq_rev + 1))
    libq_next="${libq_prefix}-${libq_next_rev}-SNAPSHOT"
    ;;
  *)
    echo "ERROR: libquackwire version in build.sbt is '$libq_current' (not -SNAPSHOT)." >&2
    echo "  release.sh expects to find a -SNAPSHOT version to promote." >&2
    exit 1
    ;;
esac

echo "==========================================================="
echo " quack-on-demand release"
echo "   manager current:    $current_version"
echo "   manager release as: $release_version"
echo "   manager next:       ${NEXT_VERSION:-(sbt-release default = bump patch + -SNAPSHOT)}"
echo "   libquackwire cur:   $libq_current"
echo "   libquackwire rel:   $libq_release"
echo "   libquackwire next:  $libq_next"
echo "   docker push:        $([[ "$NO_DOCKER" == "1" ]] && echo skipped || echo "yes -> $REGISTRY_IMAGE")"
echo "==========================================================="
echo "Proceed? [y/N]"
read -r confirm
[[ "$confirm" =~ ^[Yy]$ ]] || exit 1

# ---- libquackwire release ------------------------------------------------
# Promotes libquackwireVersion in build.sbt from -SNAPSHOT to release,
# builds all 4 platform binaries, publishSigned + sonatypeBundleRelease to
# Maven Central, then bumps to rev+1-SNAPSHOT for the next dev cycle.
#
# The host-platform binary is built locally via cmake. The other 3 are
# downloaded from the latest successful `quackwire native build` run on
# main - same matrix CI uses anyway, so we get bit-identical binaries
# without setting up cross-compile toolchains on the dev box.
echo
echo "--- libquackwire $libq_release -------------------------------------"

# 1. Detect host platform - mirrors QuackNativeBridge.NativeLoader.platformDir().
case "$(uname -s)" in
  Darwin) host_os=osx;     host_ext=dylib ;;
  Linux)  host_os=linux;   host_ext=so    ;;
  *) echo "ERROR: unsupported OS for libquackwire: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  host_arch=x86_64  ;;
  aarch64|arm64) host_arch=aarch64 ;;
  *) echo "ERROR: unsupported arch for libquackwire: $(uname -m)" >&2; exit 1 ;;
esac
host_platform="$host_os-$host_arch"

# 2. Strip -SNAPSHOT from libquackwire's version in build.sbt, commit.
# Done up-front (even if we skip the publish below) so the manager build
# pins the release coord, not the SNAPSHOT.
echo "promoting libquackwire build.sbt: $libq_current -> $libq_release"
sed -i.bak -E "s|^val libquackwireVersion = \".*\"$|val libquackwireVersion = \"${libq_release}\"|" build.sbt
rm build.sbt.bak
git add build.sbt
git commit -m "release: libquackwire ${libq_release}" -q

# 3. Is this libquackwire release already on Maven Central?
# Maven Central is immutable; re-publishing the same coord fails with an
# opaque 4xx from `sonatypeBundleRelease`. Detect it up-front and skip the
# build + publish steps so re-runs after a partial failure are idempotent.
# If the C++ source has changed since that release, the operator is
# expected to bump the rev manually in build.sbt before re-running.
pom_url="https://repo1.maven.org/maven2/ai/starlake/libquackwire/${libq_release}/libquackwire-${libq_release}.pom"
if curl -sfI "$pom_url" >/dev/null 2>&1; then
  libq_already_released=1
  echo "libquackwire ${libq_release} is already on Maven Central; skipping native build + publish."
  echo "  (if the C++ has changed since ${libq_release}, abort, bump rev in build.sbt, then re-run.)"
else
  libq_already_released=0
fi

if [[ "$libq_already_released" == "0" ]]; then
  # 3a. Build the host platform.
  echo "cmake build [$host_platform]..."
  ( cd native/quackwire \
    && cmake -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --config Release )
  mkdir -p "libquackwire/binaries/$host_platform"
  cp "native/quackwire/build/libquackwire.$host_ext" \
     "libquackwire/binaries/$host_platform/libquackwire.$host_ext"

  # 4. Download the other 3 platforms' binaries from the latest successful
  # quackwire native build CI run. We pick the most-recent green run on main
  # so the artifacts we ship match a CI-built tree. Reusing CI saves us from
  # wrangling docker buildx + QEMU + multiple libduckdb fetches locally.
  echo "looking up the latest 'quackwire native build' run on main..."
  ci_run_id=$(gh run list --workflow=quackwire.yml --branch=main --status=success \
              --limit=1 --json databaseId --jq '.[0].databaseId')
  if [[ -z "$ci_run_id" || "$ci_run_id" == "null" ]]; then
    echo "ERROR: no successful quackwire native build run on main yet." >&2
    echo "  Push a libquackwire-touching change first and wait for the CI to land." >&2
    exit 1
  fi
  echo "downloading 4-platform artifacts from CI run $ci_run_id..."
  tmp_artifacts=$(mktemp -d)
  gh run download "$ci_run_id" --dir "$tmp_artifacts" --pattern 'quackwire-*'
  for d in "$tmp_artifacts"/quackwire-*; do
    plat=$(basename "$d" | sed 's/^quackwire-//')
    [[ "$plat" == "$host_platform" ]] && continue
    mkdir -p "libquackwire/binaries/$plat"
    cp "$d"/libquackwire.* "libquackwire/binaries/$plat/"
  done
  rm -rf "$tmp_artifacts"
  echo "staged 4 platforms:"; ls libquackwire/binaries/*/

  # 5. publishSigned + sonatypeBundleRelease.
  echo "sbt libquackwire/publishSigned + sonatypeBundleRelease..."
  SBT_OPTS="${SBT_OPTS:--Xss8M -Xmx5g -XX:+UseG1GC}" \
  sbt -no-colors \
    "set ThisBuild / pgpPassphrase := Some(\"$PGP_PASSPHRASE\".toCharArray)" \
    "libquackwire/publishSigned" \
    "sonatypeBundleRelease"
fi

# 6. Bump libquackwire to next snapshot, commit.
echo "bumping libquackwire build.sbt: $libq_release -> $libq_next"
sed -i.bak -E "s|^val libquackwireVersion = \".*\"$|val libquackwireVersion = \"${libq_next}\"|" build.sbt
rm build.sbt.bak
git add build.sbt
git commit -m "next snapshot: libquackwire ${libq_next}" -q

echo "libquackwire $libq_release published to Maven Central."
echo

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