#!/usr/bin/env bash
#
# Release phase 1/3: build + publish the native libquackwire jars to Maven Central.
#
# Promotes `libquackwireVersion` in build.sbt from -SNAPSHOT to a release coord,
# commits it, builds all 4 platform binaries (host via cmake; the other 3
# downloaded from the latest green `quackwire native build` CI run on main),
# then publishSigned + sonatypeBundleRelease.
#
# There is no automatic -SNAPSHOT bump anywhere: after this release build.sbt
# keeps pinning the released (non-SNAPSHOT) coord, and manager releases reuse
# it as-is. The libquackwire version only moves when its inputs change (the
# DuckDB pin or the C++ under native/quackwire/): bump `libquackwireVersion`
# in build.sbt to a new -SNAPSHOT manually, which re-arms the CI snapshot
# publisher and this script.
#
# Idempotent: if the target coord is already on Maven Central (Central is
# immutable), the build + publish are skipped, so a re-run after a partial
# failure is safe.
#
# Required env: SONATYPE_USERNAME, SONATYPE_PASSWORD, PGP_PASSPHRASE.
#
# One-time GPG setup (if you haven't already):
#   gpg --gen-key                                # use hayssam.saleh@starlake.ai
#   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
#
# Usage:
#   ./scripts/release-libquackwire.sh

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-lib.sh"

purge_metals_scratch
require_clean_tree
require_sonatype_creds
require_pgp
require_cmd cmake "needed for the libquackwire host build"
require_gh_auth

libq_current="$(libquackwire_version)"
libq_release="$(strip_snapshot "$libq_current")"

echo "==========================================================="
echo " release libquackwire"
echo "   current: $libq_current"
echo "   release: $libq_release"
echo "==========================================================="
confirm "Proceed?" || exit 1

# 1. Promote build.sbt to the release coord + commit. Skipped when build.sbt
#    already pins the non-SNAPSHOT coord (a resumed run).
if [[ "$libq_current" == *-SNAPSHOT ]]; then
  echo "promoting libquackwire build.sbt: $libq_current -> $libq_release"
  sed -i.bak -E "s|^val libquackwireVersion = \".*\"$|val libquackwireVersion = \"${libq_release}\"|" build.sbt
  rm build.sbt.bak
  git add build.sbt
  git commit -m "release: libquackwire ${libq_release}" -q
else
  echo "build.sbt already pins ${libq_release} (non-SNAPSHOT); skipping promote/commit."
fi

# 2. Already on Central? Then nothing to build or upload.
if libquackwire_on_central "$libq_release"; then
  echo "libquackwire ${libq_release} is already on Maven Central; nothing to do."
  exit 0
fi

# 3. Build the host platform via cmake. Mirrors QuackNativeBridge platform tags.
case "$(uname -s)" in
  Darwin) host_os=osx;   host_ext=dylib ;;
  Linux)  host_os=linux; host_ext=so    ;;
  *) die "unsupported OS for libquackwire: $(uname -s)" ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  host_arch=x86_64  ;;
  aarch64|arm64) host_arch=aarch64 ;;
  *) die "unsupported arch for libquackwire: $(uname -m)" ;;
esac
host_platform="$host_os-$host_arch"

echo "cmake build [$host_platform]..."
( cd native/quackwire \
  && cmake -B build -DCMAKE_BUILD_TYPE=Release \
  && cmake --build build --config Release )
mkdir -p "libquackwire/binaries/$host_platform"
cp "native/quackwire/build/libquackwire.$host_ext" \
   "libquackwire/binaries/$host_platform/libquackwire.$host_ext"

# 4. Download the other 3 platforms from the latest green quackwire CI run on
#    main - same matrix CI uses, so bit-identical binaries without a local
#    cross-compile toolchain.
echo "looking up the latest 'quackwire native build' run on main..."
ci_run_id=$(gh run list --workflow=quackwire.yml --branch=main --status=success \
            --limit=1 --json databaseId --jq '.[0].databaseId')
[[ -n "$ci_run_id" && "$ci_run_id" != "null" ]] \
  || die "no successful quackwire native build run on main yet. Push a libquackwire change and wait for CI."
echo "downloading platform artifacts from CI run $ci_run_id..."
tmp_artifacts=$(mktemp -d)
gh run download "$ci_run_id" --dir "$tmp_artifacts" --pattern 'quackwire-*'
for d in "$tmp_artifacts"/quackwire-*; do
  plat=$(basename "$d" | sed 's/^quackwire-//')
  [[ "$plat" == "$host_platform" ]] && continue
  mkdir -p "libquackwire/binaries/$plat"
  # Unix artifacts carry libquackwire.{so,dylib}; the Windows artifact carries
  # the un-prefixed quackwire.dll. Copy whichever is present.
  cp "$d"/libquackwire.* "libquackwire/binaries/$plat/" 2>/dev/null || true
  cp "$d"/quackwire.dll  "libquackwire/binaries/$plat/" 2>/dev/null || true
done
rm -rf "$tmp_artifacts"
echo "staged platforms:"; ls libquackwire/binaries/*/

# Include the Windows classifier (native/windows-x86_64/quackwire.dll) in the
# release bundle now that it's staged from the CI run above.
export QOD_WITH_WINDOWS_NATIVE=true

# 5. publishSigned + sonatypeBundleRelease for the libquackwire subproject.
#    `project libquackwire` first: sonatypeBundleRelease checks the ACTIVE
#    project's version and refuses the root's -SNAPSHOT.
echo "sbt libquackwire/publishSigned + sonatypeBundleRelease..."
sbt_signed "project libquackwire" "publishSigned" "sonatypeBundleRelease"

echo "libquackwire ${libq_release} published to Maven Central."