#!/bin/sh
#
# quack-on-demand installer: bootstraps uv, then installs the qod CLI with it.
#
#   curl -fsSL https://raw.githubusercontent.com/starlake-ai/quack-on-demand/main/scripts/install.sh | sh
#
# Deliberately thin: all the real provisioning (release jar + sha256 verify,
# Temurin JRE, pinned duckdb CLI, node spawn scripts) lives in the qod CLI
# itself (`qod demo`), so this script never duplicates it. uv fetches its own
# Python when none is present, so there is no prerequisite beyond curl.
#
# Idempotent: re-running upgrades qod in place.
#
# Env:
#   QOD_PIP_SPEC  requirement spec to install (default "qod"); a local wheel
#                 path or "qod==X.Y.Z" pin both work.
set -eu

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required."

case "$(uname -s)" in
  Linux | Darwin) ;;
  *) die "unsupported OS $(uname -s). On Windows use: pip install qod (or uvx qod)." ;;
esac

if ! command -v uv >/dev/null 2>&1; then
  say "uv not found; installing it from https://astral.sh/uv ..."
  curl -LsSf https://astral.sh/uv/install.sh | sh || die "uv install failed."
  # The uv installer targets ~/.local/bin (XDG_BIN_HOME when set); pick it up
  # for the rest of this run without requiring a new shell.
  PATH="${XDG_BIN_HOME:-$HOME/.local/bin}:$PATH"
  export PATH
  command -v uv >/dev/null 2>&1 \
    || die "uv was installed but is not on PATH; open a new shell and re-run this script."
fi

spec="${QOD_PIP_SPEC:-qod}"
say "installing qod ($spec)..."
uv tool install --force --upgrade "$spec" || die "uv tool install $spec failed."

bin_dir="$(uv tool dir --bin 2>/dev/null || printf '%s' "$HOME/.local/bin")"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *)
    say ""
    say "NOTE: $bin_dir is not on your PATH. Asking uv to add it to your shell config:"
    uv tool update-shell || true
    say "for the current shell, run:"
    say "  export PATH=\"$bin_dir:\$PATH\""
    ;;
esac

say ""
say "qod installed: $("$bin_dir/qod" --version 2>/dev/null || printf 'run: qod --version')"
say "try the self-contained demo (embedded Postgres, seeded TPC-H, RLS/CLS):"
say "  qod demo"
