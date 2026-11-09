# shellcheck shell=bash
#
# The newest glibc the Linux native binaries (qod-node, libquackwire) may require.
#
# A binary links the symbol versions of the glibc it is built against, so a build on a recent
# distribution refuses to start on an older one ("version `GLIBC_2.34' not found"). The official
# libduckdb needs far less, which makes these two binaries the only thing that would raise the
# requirement. CI therefore builds them in a manylinux_2_28 container (.github/workflows/qod-node.yml
# and quackwire.yml), and this check holds the result to that floor wherever a binary is produced or
# shipped. 2.28 covers Debian 10+, Ubuntu 18.10+ and RHEL 8+.
#
# Sourced by qod-node.yml, quackwire.yml, the refresh scripts and release-lib.sh. Reads the version
# strings out of the file without running it, so it works for every Linux platform on any host. Needs
# only grep, sed, sort and awk.

GLIBC_FLOOR=2.28

# glibc_floor_check <elf> - silent when <elf> needs no glibc newer than GLIBC_FLOOR, else says why on
# stderr and returns 1.
glibc_floor_check() {
  local file="$1" newest
  newest="$(LC_ALL=C grep -a -o 'GLIBC_[0-9][0-9.]*[0-9]' "$file" 2>/dev/null \
    | sed 's/^GLIBC_//' \
    | awk -F. '{ printf "%05d.%05d %s\n", $1, $2, $0 }' \
    | sort | tail -n1 | cut -d' ' -f2)"
  [[ -n "$newest" ]] || { echo "$file names no glibc version: not a Linux binary?" >&2; return 1; }
  if ! awk -v have="$newest" -v floor="$GLIBC_FLOOR" 'BEGIN {
    split(have, h, "."); split(floor, f, ".")
    exit !(h[1] < f[1] || (h[1] == f[1] && h[2] <= f[2]))
  }'; then
    echo "$file needs glibc $newest; the limit is $GLIBC_FLOOR. Build it in the manylinux_2_28 container CI uses (see scripts/release/glibc-floor.sh)." >&2
    return 1
  fi
}
