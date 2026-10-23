# shellcheck shell=bash
#
# Reads and checks the build marker compiled into a qod-node binary (kBuildInfo in
# native/qod-node/src/build_info.cpp, values supplied by native/qod-node/CMakeLists.txt):
#
#   qod-node-build-info:protocol=1;duckdb=v2.0.0;duckdb_source=<id>
#
# native/qod-node/binaries/VERSION is stamped for the whole set, so it cannot tell a binary left
# over from an earlier DuckDB from a fresh one; the marker can. The manager runs the same check at
# boot by running the binary (QodNodeBinary.check); this one reads the files without running them,
# so it works for every platform on any host.
#
# Sourced by refresh-qod-node-binaries.sh and release-lib.sh (verify_qod_node_binaries). Needs only
# grep and sed; expects the repo root as the current directory.

# The platforms the manager jar bundles a qod-node for (build.sbt). Windows is a release asset.
QOD_NODE_UNIX_PLATFORMS=(linux-x86_64 linux-aarch64 osx-x86_64 osx-aarch64)

# The node protocol the sources compile in (QodNodeBinarySpec pins QodNodeBinary.Protocol to it).
qod_node_pin_protocol() {
  sed -nE 's/^set\(QOD_NODE_PROTOCOL ([0-9]+)\).*/\1/p' native/qod-node/CMakeLists.txt
}

# The DuckDB release every qod-node of this build links, as its marker spells it: v<x.y.z> of
# Versions.duckdb.
qod_node_pin_duckdb() {
  local v
  v="$(sed -nE 's/^ *val duckdb *= *"([0-9]+\.[0-9]+\.[0-9]+)[^"]*".*/\1/p' project/Versions.scala)"
  [[ -n "$v" ]] && echo "v$v"
}

# qod_node_build_marker <file> - prints the marker, or nothing when the binary has none. The literal
# is plain ASCII in every format (ELF .rodata, Mach-O __cstring, PE .rdata).
qod_node_build_marker() {
  LC_ALL=C grep -a -o 'qod-node-build-info:[ -~]*' "$1" 2>/dev/null | head -n1
}

# qod_node_check_marker <file> - prints the binary's DuckDB source id when its marker matches the
# pin (node protocol, DuckDB release), else says why on stderr and returns 1.
qod_node_check_marker() {
  local file="$1" protocol duckdb marker
  protocol="$(qod_node_pin_protocol)"
  duckdb="$(qod_node_pin_duckdb)"
  [[ -n "$protocol" && -n "$duckdb" ]] \
    || { echo "cannot read the qod-node pin (QOD_NODE_PROTOCOL in native/qod-node/CMakeLists.txt, Versions.duckdb in project/Versions.scala)" >&2; return 1; }
  marker="$(qod_node_build_marker "$file")"
  if [[ ! "$marker" =~ ^qod-node-build-info:protocol=([0-9]+)\;duckdb=(v[0-9.]+)\;duckdb_source=([0-9a-f]+)$ ]]; then
    echo "$file has no qod-node build marker" >&2
    return 1
  fi
  if [[ "${BASH_REMATCH[1]}" != "$protocol" || "${BASH_REMATCH[2]}" != "$duckdb" ]]; then
    echo "$file is built for node protocol ${BASH_REMATCH[1]} and DuckDB ${BASH_REMATCH[2]}; the pin is protocol $protocol, DuckDB $duckdb. Run scripts/release/refresh-qod-node-binaries.sh." >&2
    return 1
  fi
  echo "${BASH_REMATCH[3]}"
}
