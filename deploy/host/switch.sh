#!/usr/bin/env bash
# Point the manager at an installed qod release and restart it.
#
#   switch.sh VERSION [--no-restart]
#
# Refuses a version install.sh has not installed. Checks that version's cache first (install.sh
# again, which downloads nothing when the cache is complete), so a restart never depends on the
# network; then writes /etc/qod/version.env and runs `systemctl restart qod-manager`. Rolling back
# is the same command with the earlier version.
#
# Needs root. Environment, for tests (defaults in brackets): QOD_HOST_PREFIX [/opt/qod],
# QOD_HOST_ETC [/etc/qod], SYSTEMCTL [systemctl], QOD_HOST_UNIT [qod-manager], and those of
# install.sh.

set -eu

PREFIX="${QOD_HOST_PREFIX:-/opt/qod}"
ETC="${QOD_HOST_ETC:-/etc/qod}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"
UNIT="${QOD_HOST_UNIT:-qod-manager}"

die() {
  echo "switch.sh: $*" >&2
  exit 1
}

usage() {
  echo "usage: switch.sh VERSION [--no-restart]" >&2
  exit 2
}

restart=1
case "$#" in
  1) ;;
  2) [ "$2" = "--no-restart" ] || usage; restart="" ;;
  *) usage ;;
esac
VERSION="$1"
case "$VERSION" in
  "" | *[!0-9.]* | .* | *. | *..*) usage ;;
esac

if [ ! -f "$PREFIX/versions/$VERSION/.qod-installed" ]; then
  installed="$(cd "$PREFIX/versions" 2>/dev/null && for d in */.qod-installed; do
    [ -e "$d" ] && printf ' %s' "${d%/.qod-installed}"
  done || true)"
  die "qod $VERSION is not installed in $PREFIX/versions (installed:${installed:- none}); run install.sh $VERSION first"
fi

"$(dirname "$0")/install.sh" "$VERSION" >/dev/null ||
  die "qod $VERSION cannot run from its cache (see above); the pin was not changed"

previous="$(sed -n 's/^QOD_VERSION=//p' "$ETC/version.env" 2>/dev/null | tail -n 1 || true)"
tmp="$(mktemp "$ETC/.version.env.XXXXXX")"
echo "QOD_VERSION=$VERSION" >"$tmp"
chmod 0644 "$tmp"
mv -f "$tmp" "$ETC/version.env"
echo "pinned $VERSION in $ETC/version.env${previous:+ (was $previous)}"

if [ -n "$restart" ]; then
  "$SYSTEMCTL" restart "$UNIT"
  echo "restarted $UNIT"
fi
if [ -n "$previous" ] && [ "$previous" != "$VERSION" ]; then
  echo "to roll back: switch.sh $previous"
fi
