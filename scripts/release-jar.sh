#!/usr/bin/env bash
#
# Release phase 2/3: version-set, tag, publish the manager jar, cut the GitHub
# release, then bump to the next dev snapshot and push.
#
# This does by hand, idempotently, what sbt-release's `releaseProcess` did
# atomically (build.sbt: setReleaseVersion -> commit -> tag -> publishSigned ->
# sonatypeBundleRelease -> setNextVersion -> commit -> push). Doing the steps
# explicitly is the whole point of the split: any step that a network failure
# interrupted can be resumed by simply re-running this script. Each step
# no-ops the work it detects is already done:
#   - version.sbt already at the release version -> skip the set + commit
#   - tag v<version> already exists              -> skip tag
#   - manager already on Maven Central           -> skip publish
#   - qod-cli already on PyPI                    -> skip PyPI publish
#   - GitHub release already exists              -> skip gh release
#   - version.sbt already bumped to -SNAPSHOT    -> skip the finalize bumps
#
# Prerequisite: libquackwire must already be released (build.sbt pins a
# non-SNAPSHOT coord that is on Maven Central) - run release-libquackwire.sh
# first. This mirrors sbt-release's checkSnapshotDependencies gate.
#
# Required env: SONATYPE_USERNAME, SONATYPE_PASSWORD, PGP_PASSPHRASE, PYPI_TOKEN.
# Optional env: RELEASE_VERSION (pin the release; default = strip -SNAPSHOT),
#               NEXT_VERSION    (pin the next snapshot; default = bump patch),
#               QOD_DISCORD_WEBHOOK_URL (announce on Discord #news; also read
#               from the untracked .env - see announce-release-discord.sh).
#
# Usage:
#   ./scripts/release-jar.sh

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-lib.sh"

purge_metals_scratch
require_clean_tree
warn_if_not_main
require_sonatype_creds
require_pgp
require_cmd python3
require_pypi_creds
require_cmd gh
gh auth status >/dev/null 2>&1 || die "'gh auth login' first (needed to create the GitHub release)."

# ---- Gate: libquackwire must be released (checkSnapshotDependencies) ----
libq="$(libquackwire_version)"
[[ "$libq" != *-SNAPSHOT ]] \
  || die "build.sbt pins a -SNAPSHOT libquackwire ($libq). Run ./scripts/release-libquackwire.sh first."
libquackwire_on_central "$libq" \
  || die "libquackwire $libq is not on Maven Central yet. Run ./scripts/release-libquackwire.sh first."

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
echo " release manager jar"
echo "   version.sbt now: $current"
echo "   release as:      $release_version"
echo "   next snapshot:   $next_version"
echo "   libquackwire:    $libq (on Central)"
echo "==========================================================="
confirm "Proceed?" || exit 1

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

# qod-cli __version__ tracks the manager release version in lockstep; stamp it
# even on a resumed run so it lands regardless of where a prior run stopped.
if [[ "$(cli_version)" != "$release_version" ]]; then
  echo "setting cli __version__ -> $release_version"
  set_cli_version "$release_version"
  git add cli/src/qod_cli/__init__.py
  git diff --cached --quiet || git commit -m "Setting qod-cli version to ${release_version}" -q
fi

# ---- 2. Tag (idempotent) -------------------------------------------------
if git rev-parse -q --verify "refs/tags/v${release_version}" >/dev/null; then
  echo "tag v${release_version} already exists; skipping."
else
  echo "tagging v${release_version}"
  git tag "v${release_version}"
fi

# ---- 3. publishSigned + sonatypeBundleRelease (idempotent) ---------------
# Bundle the Windows classifier so the released uber-jar runs the native client
# on Windows AND Linux/macOS (matches the snapshot workflow). Requires the
# windows-x86_64 libquackwire classifier to already be on Maven Central -
# release-libquackwire.sh publishes it (its CI-artifact stage now includes
# quackwire.dll).
export QOD_WITH_WINDOWS_NATIVE=true
if manager_on_central "$release_version"; then
  echo "manager ${release_version} already on Maven Central; skipping publish."
else
  echo "sbt publishSigned + sonatypeBundleRelease (manager)..."
  sbt_signed "publishSigned" "sonatypeBundleRelease"
fi

# ---- 3b. PyPI: qod-cli (idempotent) ---------------------------------------
if cli_on_pypi "$release_version"; then
  echo "qod-cli ${release_version} already on PyPI; skipping."
else
  echo "building + publishing qod-cli ${release_version} to PyPI..."
  ( cd "$REPO_DIR/cli"
    rm -rf dist
    "$(pypi_python)" -m build
    TWINE_USERNAME=__token__ TWINE_PASSWORD="$PYPI_TOKEN" "$(pypi_python)" -m twine upload dist/*
  )
fi

# ---- 4. Finalize: bump to next dev snapshots + push commits + tag --------
# Push must happen BEFORE the GitHub release: `gh release create` requires the
# tag to already exist on the remote. Bumps are idempotent (skip once the
# version is -SNAPSHOT); the pushes are unconditional and no-op when already
# up to date, so a resumed run still pushes an unpushed tag.
if [[ "$(manager_version)" != *-SNAPSHOT ]]; then
  echo "bumping version.sbt -> $next_version"
  sed -i.bak -E "s|\"[^\"]+\"|\"${next_version}\"|" version.sbt
  rm version.sbt.bak
  git add version.sbt
  git commit -m "Setting version to ${next_version}" -q
fi

cli_next="${next_version%-SNAPSHOT}.dev0"
if [[ "$(cli_version)" != "$cli_next" ]]; then
  echo "bumping cli __version__ -> $cli_next"
  set_cli_version "$cli_next"
  git add cli/src/qod_cli/__init__.py
  git commit -m "next dev version: qod-cli ${cli_next}" -q
fi

# libquackwire back to its next -SNAPSHOT for the next dev cycle. Done here,
# after the manager release, so the release build kept pinning the non-SNAPSHOT
# coord throughout.
if [[ "$(libquackwire_version)" != *-SNAPSHOT ]]; then
  libq_next="$(next_libquackwire_snapshot "$(libquackwire_version)")"
  echo "bumping libquackwire build.sbt -> $libq_next"
  sed -i.bak -E "s|^val libquackwireVersion = \".*\"$|val libquackwireVersion = \"${libq_next}\"|" build.sbt
  rm build.sbt.bak
  git add build.sbt
  git commit -m "next snapshot: libquackwire ${libq_next}" -q
fi

echo "pushing commits + tag to origin..."
git push origin HEAD
git push origin "v${release_version}"

# ---- 5. GitHub release (idempotent) --------------------------------------
if gh release view "v${release_version}" >/dev/null 2>&1; then
  echo "GitHub release v${release_version} already exists; skipping."
else
  jar="distrib/quack-on-demand-assembly-${release_version}.jar"
  if [[ ! -f "$jar" ]]; then
    echo "assembly jar missing; building $jar..."
    sbt assembly
  fi
  echo "creating GitHub release v${release_version}"
  gh release create "v${release_version}" \
    --title "v${release_version}" \
    --generate-notes \
    "$jar"

  # ---- 5b. Discord #news announcement (best-effort) ----------------------
  # Inside the create branch on purpose: a resumed run that found the GH
  # release already present must not re-announce. If the announce itself
  # fails (or the webhook is unset), retry manually with the printed command.
  "$REPO_DIR/scripts/announce-release-discord.sh" "$release_version" \
    || echo "WARNING: Discord announce failed; retry with ./scripts/announce-release-discord.sh ${release_version}"
fi

echo
echo "manager release complete."
echo "  - tag:  v${release_version}"
echo "  - jar:  https://central.sonatype.com/artifact/ai.starlake/quack-on-demand_3/${release_version}"
echo "  - next: $(manager_version)"
echo "Run ./scripts/release-docker.sh to push the Docker image."