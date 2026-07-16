#!/usr/bin/env bash
#
# Shared helpers for the split release scripts:
#   release-libquackwire.sh  - build + publish the native libquackwire jars
#   release-jar.sh           - version-set, tag, publish the manager, GH release
#   release-docker.sh        - multi-arch Docker image
#   release.sh               - orchestrator that runs the three in order
#
# Source this file; do not execute it. It anchors CWD at the repo root and
# exposes the version-math + Maven-Central idempotency helpers every phase
# needs. The phases are individually re-runnable: each one no-ops the work it
# detects is already done (coord already on Central, tag already present,
# version.sbt already bumped), so a mid-release network failure is resumed by
# simply running the failed phase again.

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

require_sonatype_creds() {
  : "${SONATYPE_USERNAME:?SONATYPE_USERNAME is required - Central Portal user-token name}"
  : "${SONATYPE_PASSWORD:?SONATYPE_PASSWORD is required - Central Portal user-token secret}"
}

require_pgp() {
  : "${PGP_PASSPHRASE:?PGP_PASSPHRASE is required - GPG signing key passphrase}"
  command -v gpg >/dev/null 2>&1 || die "gpg not on PATH. Install GnuPG before releasing."
  gpg --list-secret-keys --keyid-format LONG 2>/dev/null | grep -q '^sec' \
    || die "no GPG secret key in keyring. See release-libquackwire.sh header for setup."
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "$1 not on PATH${2:+ ($2)}."; }

require_gh_auth() {
  require_cmd gh "needed to download the non-host libquackwire binaries from CI"
  gh auth status >/dev/null 2>&1 || die "'gh auth login' first - we download artifacts from quackwire.yml runs."
}

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

# 0.3.5 -> 0.3.6-SNAPSHOT (sbt-release's default patch bump).
next_manager_snapshot() {
  local v; v="$(strip_snapshot "$1")"
  local a b c; IFS=. read -r a b c <<<"$v"
  echo "${a}.${b}.$((c + 1))-SNAPSHOT"
}

# 1.5.4-<hash>-2 -> 1.5.4-<hash>-3-SNAPSHOT (rev+1, per build.sbt's version format).
next_libquackwire_snapshot() {
  local rel; rel="$(strip_snapshot "$1")"
  local rev="${rel##*-}" prefix="${rel%-*}"
  echo "${prefix}-$((rev + 1))-SNAPSHOT"
}

# ---- Maven Central idempotency ------------------------------------------
# Central is immutable; re-publishing the same coord fails with an opaque 4xx.
# These let each phase skip the build+publish when the artifact is already up.
on_central() { # <artifact> <version>
  curl -sfI "https://repo1.maven.org/maven2/ai/starlake/$1/$2/$1-$2.pom" >/dev/null 2>&1
}
libquackwire_on_central() { on_central libquackwire "$1"; }
manager_on_central()      { on_central quack-on-demand_3 "$1"; }

# ---- sbt with signing ----------------------------------------------------
# Injects the PGP passphrase so sbt-pgp's signing step never blocks on the
# gpg-agent prompt. Pass the sbt commands as separate args.
sbt_signed() {
  sbt -no-colors \
    "set ThisBuild / pgpPassphrase := Some(\"$PGP_PASSPHRASE\".toCharArray)" \
    "$@"
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