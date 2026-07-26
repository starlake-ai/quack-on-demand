#!/usr/bin/env bash
#
# Cut a manager release: verify, stamp the release versions, tag, push.
# That is ALL this script does - CI is the sole publisher (decided
# 2026-07-26). Pushing the v<version> tag triggers
# .github/workflows/release.yml, which builds the assembly from the tag
# (re-running the verify_quackwire_binaries gate), creates the GitHub
# release with the jar + .sha256, publishes qod + qod-cli to PyPI, pushes
# the multi-arch Docker image, announces on Discord #news, and opens the
# next-snapshot bump PR (a no-op, since this script already bumps locally).
#
# If a CI channel fails, re-run the workflow against the existing tag
# (workflow_dispatch takes the version) instead of publishing by hand.
# scripts/release-docker.sh remains only as a manual fallback for a broken
# Docker channel; nothing else publishes locally anymore.
#
# Steps are idempotent so an interrupted run can simply be re-run:
#   - version.sbt already at the release version -> skip the set + commit
#   - tag v<version> already exists              -> skip tag
#   - version.sbt already bumped to -SNAPSHOT    -> skip the finalize bumps
#   - pushes no-op when already up to date
#
# Prerequisite: the vendored libquackwire binaries must match
# `libquackwireVersion` in build.sbt (refresh with
# scripts/refresh-quackwire-binaries.sh). No PyPI/Docker/PGP/Sonatype
# credentials are needed here - the CI workflow holds the publish secrets.
#
# Optional env: RELEASE_VERSION (pin the release; default = strip -SNAPSHOT),
#               NEXT_VERSION    (pin the next snapshot; default = bump patch),
#               RELEASE_YES=1   (skip the confirmation prompt).
#
# Usage:
#   ./scripts/release.sh
#   RELEASE_VERSION=0.6.0 NEXT_VERSION=0.7.0-SNAPSHOT ./scripts/release.sh

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-lib.sh"

purge_metals_scratch
require_clean_tree
warn_if_not_main

# ---- Gate: vendored libquackwire binaries match the pin -------------------
libq="$(libquackwire_version)"
verify_quackwire_binaries \
  || die "vendored libquackwire binaries are stale or missing for $libq. Run ./scripts/refresh-quackwire-binaries.sh first."

# ---- Resolve versions ----------------------------------------------------
current="$(manager_version)"
if [[ "$current" == *-SNAPSHOT ]]; then
  release_version="${RELEASE_VERSION:-$(strip_snapshot "$current")}"
else
  # Resumed run: version.sbt already holds the release version.
  release_version="${RELEASE_VERSION:-$current}"
fi
next_version="${NEXT_VERSION:-$(next_manager_snapshot "$release_version")}"

echo "==========================================================="
echo " cut manager release v${release_version} (CI publishes)"
echo "   version.sbt now: $current"
echo "   next snapshot:   $next_version"
echo "   libquackwire:    $libq (vendored)"
echo "==========================================================="
if [[ "${RELEASE_YES:-0}" != "1" ]]; then
  confirm "Proceed?" || exit 1
fi

# ---- 1. Set release version + commit (idempotent) ------------------------
if [[ "$(manager_version)" == *-SNAPSHOT ]]; then
  echo "setting version.sbt -> $release_version"
  sed -i.bak -E "s|\"[^\"]+\"|\"${release_version}\"|" version.sbt
  rm version.sbt.bak
  git add version.sbt
  git commit -m "Setting version to ${release_version}" -q
else
  echo "version.sbt already at ${release_version}; skipping set/commit."
fi

# The CLI __version__ tracks the manager release version in lockstep; stamp it
# even on a resumed run so it lands regardless of where a prior run stopped.
# The qod-cli shim pyproject is stamped alongside (version + qod== pin).
# Load-bearing for CI: release.yml's pypi job refuses to publish when these
# do not equal the tag's version (the drift guard).
if [[ "$(cli_version)" != "$release_version" ]]; then
  echo "setting cli __version__ -> $release_version"
  set_cli_version "$release_version"
  git add cli/src/qod_cli/__init__.py
fi
if [[ "$(cli_manager_version)" != "$release_version" ]]; then
  echo "setting cli MANAGER_VERSION -> $release_version"
  set_cli_manager_version "$release_version"
  git add cli/src/qod_cli/_manager_version.py
fi
set_cli_shim_version "$release_version"
git add cli/shim-qod-cli/pyproject.toml
git diff --cached --quiet || git commit -m "Setting qod version to ${release_version}" -q

# ---- 2. Tag (idempotent) -------------------------------------------------
if git rev-parse -q --verify "refs/tags/v${release_version}" >/dev/null; then
  echo "tag v${release_version} already exists; skipping."
else
  echo "tagging v${release_version}"
  git tag "v${release_version}"
fi

# ---- 3. Bump to next dev snapshots (idempotent) ---------------------------
# Done locally so main returns to -SNAPSHOT in the same push; release.yml's
# next-snapshot job then finds an empty diff and self-neutralizes.
if [[ "$(manager_version)" != *-SNAPSHOT ]]; then
  echo "bumping version.sbt -> $next_version"
  sed -i.bak -E "s|\"[^\"]+\"|\"${next_version}\"|" version.sbt
  rm version.sbt.bak
  git add version.sbt
  git commit -m "Setting version to ${next_version}" -q
fi

cli_next="${next_version%-SNAPSHOT}.dev0"
if [[ "$(cli_version)" != "$cli_next" || "$(cli_manager_version)" != "$cli_next" ]]; then
  echo "bumping cli __version__ + MANAGER_VERSION -> $cli_next"
  set_cli_version "$cli_next"
  set_cli_manager_version "$cli_next"
  git add cli/src/qod_cli/__init__.py cli/src/qod_cli/_manager_version.py
  git commit -m "next dev version: qod-cli ${cli_next}" -q
fi

# libquackwireVersion is deliberately NOT bumped here. It only changes when
# its inputs change (the DuckDB pin or the C++ under native/quackwire/): edit
# the val in build.sbt manually, push (CI builds the new binaries), then run
# scripts/refresh-quackwire-binaries.sh and commit the diff. Auto-bumping per
# manager release would mint a new pin every cycle with zero native changes.

# ---- 4. Push commits + tag: the tag push IS the publish trigger -----------
echo "pushing commits + tag to origin (the tag push starts the release workflow)..."
git push origin HEAD
git push origin "v${release_version}"

echo
echo "release v${release_version} cut. CI is publishing:"
echo "  - watch:   gh run watch \$(gh run list --workflow=release.yml --limit=1 --json databaseId --jq '.[0].databaseId')"
echo "  - release: https://github.com/starlake-ai/quack-on-demand/releases/tag/v${release_version} (appears when the workflow finishes)"
echo "  - next:    $(manager_version)"
