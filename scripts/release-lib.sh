#!/usr/bin/env bash
#
# Shared helpers for the split release scripts:
#   release-jar.sh           - version-set, tag, publish the manager, GH release
#   release-docker.sh        - multi-arch Docker image
#   release.sh               - orchestrator that runs both in order, plus the
#                               libquackwire vendored-binaries verification
#
# Source this file; do not execute it. It anchors CWD at the repo root and
# exposes the version-math + PyPI/GitHub-release idempotency helpers every
# phase needs, plus verify_quackwire_binaries (the vendored-binaries check
# shared by release.sh's phase 1 and release-jar.sh's pre-publish gate - see
# scripts/refresh-quackwire-binaries.sh for how the binaries themselves get
# refreshed). The phases are individually re-runnable: each one no-ops the
# work it detects is already done (tag already present, version.sbt already
# bumped), so a mid-release network failure is resumed by simply running the
# failed phase again.

# Repo root, derived from this file's own location (scripts/release-lib.sh).
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

REGISTRY_IMAGE="${REGISTRY_IMAGE:-starlakeai/quack-on-demand}"
# Larger heap/stack for the signed publish + scaladoc steps.
SBT_OPTS="${SBT_OPTS:--Xss8M -Xmx5g -XX:+UseG1GC}"
export SBT_OPTS

# ---- terminal / scratch hygiene -----------------------------------------
# Metals' Scala-CLI worksheets drop `.metals-scala-cli/` + `.scala-build/`
# scratch trees compiled with Metals' own (newer) Scala; sbt copies their
# `.tasty` into target/classes and scaladoc aborts with "Forward incompatible
# TASTy file". The release skips `clean`, so nuke them up-front.
purge_metals_scratch() {
  echo "purging Metals Scala-CLI scratch dirs (.metals-scala-cli / .scala-build)..."
  find src target -type d \( -name '.metals-scala-cli' -o -name '.scala-build' \) \
    -prune -exec rm -rf {} + 2>/dev/null || true
}

# ---- preflight helpers ---------------------------------------------------
die() { echo "ERROR: $*" >&2; exit 1; }

require_clean_tree() {
  [[ -z "$(git status --porcelain)" ]] || {
    echo "ERROR: working tree is dirty. Commit or stash before releasing." >&2
    git status --short >&2
    exit 1
  }
}

warn_if_not_main() {
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD)"
  if [[ "$branch" != "main" ]]; then
    if [[ "${RELEASE_YES:-0}" == "1" ]]; then
      echo "WARN: releasing from '$branch', not 'main' (RELEASE_YES=1, continuing)." >&2
    else
      echo "WARN: releasing from '$branch', not 'main'. Continue? [y/N]" >&2
      read -r ans
      [[ "$ans" =~ ^[Yy]$ ]] || exit 1
    fi
  fi
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "$1 not on PATH${2:+ ($2)}."; }

# confirm <prompt> - honors RELEASE_YES=1 for non-interactive / orchestrated runs.
confirm() {
  [[ "${RELEASE_YES:-0}" == "1" ]] && return 0
  echo "$1 [y/N]"
  read -r ans
  [[ "$ans" =~ ^[Yy]$ ]]
}

# ---- version math --------------------------------------------------------
manager_version()  { grep -E '^ThisBuild / version' version.sbt | sed -E 's/.*"([^"]+)".*/\1/'; }
libquackwire_version() { grep -E '^val libquackwireVersion' build.sbt | sed -E 's/.*"([^"]+)".*/\1/'; }

strip_snapshot() { echo "${1%-SNAPSHOT}"; }

# sha256_of <file> - prints the hex digest. macOS ships shasum; Linux usually
# ships sha256sum instead (and may lack shasum entirely).
sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# ---- vendored libquackwire verification ----------------------------------
# Replaces the old Maven-Central-availability gate: binaries are vendored in
# git now (refreshed by scripts/refresh-quackwire-binaries.sh), so "is
# libquackwire safe to ship with this manager release" means "does the
# working tree's libquackwire/binaries/ match the pinned version and its own
# checksums" rather than "is it on Central". Shared by release.sh's phase 1
# and release-jar.sh's pre-publish gate.
verify_quackwire_binaries() {
  local version stamped
  version="$(grep -E '^val libquackwireVersion' build.sbt | sed -E 's/.*"(.*)".*/\1/')"
  stamped="$(cat libquackwire/binaries/VERSION 2>/dev/null || true)"
  [[ "$stamped" == "$version" ]] \
    || { echo "libquackwire/binaries/VERSION ($stamped) != libquackwireVersion ($version). Run scripts/refresh-quackwire-binaries.sh." >&2; return 1; }
  local f
  for f in libquackwire/binaries/*/libquackwire.* libquackwire/binaries/*/quackwire.dll; do
    [[ -f "$f" && "$f" != *.sha256 ]] || continue
    [[ -f "$f.sha256" ]] || { echo "missing checksum $f.sha256" >&2; return 1; }
    [[ "$(sha256_of "$f")" == "$(cat "$f.sha256")" ]] \
      || { echo "checksum mismatch for $f. Run scripts/refresh-quackwire-binaries.sh." >&2; return 1; }
  done
  echo "phase 1 OK: vendored libquackwire binaries match $version"
}

# 0.3.5 -> 0.3.6-SNAPSHOT (sbt-release's default patch bump).
next_manager_snapshot() {
  local v; v="$(strip_snapshot "$1")"
  local a b c; IFS=. read -r a b c <<<"$v"
  echo "${a}.${b}.$((c + 1))-SNAPSHOT"
}

# ---- qod CLI (PyPI) --------------------------------------------------------
# The CLI publishes as `qod`; `qod-cli` (the original name) is a shim package
# that pins the matching qod release. Both ship from every release.
CLI_VERSION_FILE="$REPO_DIR/cli/src/qod_cli/__init__.py"
CLI_SHIM_PYPROJECT="$REPO_DIR/cli/shim-qod-cli/pyproject.toml"

cli_version() {
  sed -nE 's|^__version__ = "([^"]+)"$|\1|p' "$CLI_VERSION_FILE"
}

set_cli_version() {
  sed -i.bak -E "s|^__version__ = \".*\"$|__version__ = \"$1\"|" "$CLI_VERSION_FILE"
  rm "${CLI_VERSION_FILE}.bak"
}

# The manager release the CLI pairs with (resolve_jar's offline fallback),
# stamped from version.sbt alongside __version__.
MANAGER_VERSION_FILE="$REPO_DIR/cli/src/qod_cli/_manager_version.py"

cli_manager_version() {
  sed -nE 's|^MANAGER_VERSION = "([^"]+)"$|\1|p' "$MANAGER_VERSION_FILE"
}

set_cli_manager_version() {
  sed -i.bak -E "s|^MANAGER_VERSION = \".*\"$|MANAGER_VERSION = \"$1\"|" "$MANAGER_VERSION_FILE"
  rm "${MANAGER_VERSION_FILE}.bak"
}

set_cli_shim_version() {
  sed -i.bak -E \
    -e "s|^version = \".*\"$|version = \"$1\"|" \
    -e "s|\"qod==[^\"]*\"|\"qod==$1\"|" \
    "$CLI_SHIM_PYPROJECT"
  rm "${CLI_SHIM_PYPROJECT}.bak"
}

cli_on_pypi() {
  curl -fsS -o /dev/null "https://pypi.org/pypi/qod/$1/json"
}

cli_shim_on_pypi() {
  curl -fsS -o /dev/null "https://pypi.org/pypi/qod-cli/$1/json"
}

# build + twine live in a repo-local venv so the release never depends on
# whatever python3 happens to be first on PATH (a stray project venv, a
# bare system python). Provisioned on demand, reused across runs.
PYPI_VENV="$REPO_DIR/.venv-release"

pypi_python() {
  if [[ -x "$PYPI_VENV/bin/python" ]]; then
    echo "$PYPI_VENV/bin/python"
  else
    echo "$PYPI_VENV/Scripts/python.exe"   # Windows venv layout
  fi
}

ensure_pypi_tooling() {
  if "$(pypi_python)" -c "import build, twine" >/dev/null 2>&1; then
    return 0
  fi
  echo "provisioning $PYPI_VENV with build + twine (one-time)..."
  python3 -m venv "$PYPI_VENV" \
    || die "python3 -m venv failed; install a python3 with venv support."
  "$(pypi_python)" -m pip install --quiet --upgrade pip build twine \
    || die "pip install build twine failed in $PYPI_VENV."
}

require_pypi_creds() {
  [[ -n "${PYPI_TOKEN:-}" ]] || die "PYPI_TOKEN not set (PyPI API token for qod-cli)."
  ensure_pypi_tooling
}