#!/usr/bin/env bash
#
# Refuses a tree that only builds on a developer machine: a pre-release DuckDB
# JDBC (`Versions.duckdbJdbc` containing `-pre-`, minted into ~/.m2 by
# scripts/dev/build-duckdb-prerelease.sh, served by no remote repository) or a
# `Resolver.mavenLocal` in the build. Either one makes every CI sbt job, the
# manager Docker build and fresh clones fail at dependency resolution, while the
# node and worker image workflows (no sbt) would still publish on the new
# contract.
#
# Run by .github/workflows/release-pins.yml on pull requests targeting main and
# pushes to main, and as the first step of every snapshot / release publishing
# workflow. A pre-release branch is free to carry both until release day; it
# just cannot reach main.
#
# Usage: scripts/release/check-release-pins.sh [repo dir]
set -euo pipefail
REPO_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO_DIR"

problems=()

jdbc_line="$(grep -E '^[[:space:]]*val duckdbJdbc[[:space:]]*=' project/Versions.scala || true)"
[[ -n "$jdbc_line" ]] || problems+=("project/Versions.scala has no \`val duckdbJdbc\` line")
if [[ "$jdbc_line" == *-pre-* ]]; then
  problems+=("project/Versions.scala pins a pre-release DuckDB JDBC (${jdbc_line#"${jdbc_line%%[![:space:]]*}"}): it exists only in a developer's ~/.m2. Release day: \`val duckdbJdbc = duckdb\` (Maven Central).")
fi

# Comment lines do not count; anything else naming mavenLocal does.
for f in build.sbt project/*.sbt project/*.scala; do
  [[ -f "$f" ]] || continue
  while IFS= read -r hit; do
    line="${hit#*:}"
    problems+=("$f:${hit%%:*} resolves from ~/.m2 (${line#"${line%%[![:space:]]*}"}): drop the mavenLocal resolver.")
  done < <(grep -nE 'mavenLocal' "$f" | grep -vE '^[0-9]+:[[:space:]]*//' || true)
done

# ---- QoD Search: nothing shipped links qod_search_core (OWNER RULING 2026-11-16, D2) ----------------------
# While search Phase 1 is unmerged, every configure of a binary that ships names -DQOD_NODE_SEARCH=OFF, so that
# no cached ON can leak into one, and the development builds that turn it ON write build-search* trees no
# publishing step reads. Each publishing step names the setting it ships and refuses a binary built otherwise
# (scripts/release/qod-node-windows-assets.sh, the node image, refresh-qod-node-binaries.sh).
search_problems=()

# qod_node_configures <file> - one line per configure of qod-node in the file: its line number, a tab, and the
# cmake command joined over its backslash continuations, from the `cmake` word to the first `&&`, `;` or `|`.
# A configure is a cmake run (not --build, -E, -P, --install, --test-dir, --version) whose source is qod-node:
# `-S <...>native/qod-node` or `-S<...>` anywhere on the command, a bare <...>native/qod-node argument, or a
# `cd <...>native/qod-node` before it on its first line. Comment lines start none.
qod_node_configures() {
  awk '
    function flush(   head, rest, src) {
      if (!start) return
      rest = cmd
      sub(/&&.*|;.*|\|.*/, "", rest)
      src = 0
      if (rest ~ /(^|[[:space:]=])(-S[[:space:]]*)?["'\'']?[^[:space:]"'\'']*native\/qod-node\/?["'\'']?([[:space:]]|$)/) src = 1
      if (prefix ~ /(^|[^A-Za-z0-9_])cd[[:space:]]+["'\'']?[^&;|]*native\/qod-node\/?["'\'']?[[:space:]]*(&&|;)/) src = 1
      if (src && rest !~ /^cmake[[:space:]]+(--build|-E|-P|--install|--test-dir|--version)([[:space:]]|$)/) print start "\t" rest
      start = 0; cmd = ""; prefix = ""
    }
    {
      line = $0
      if (!start && line !~ /^[[:space:]]*#/ && match(line, /(^|[^A-Za-z0-9_$.\/-])cmake[[:space:]]/)) {
        start = NR
        at = RSTART + (substr(line, RSTART, 1) == "c" ? 0 : 1)
        prefix = substr(line, 1, at - 1)
        line = substr(line, at)
        cmd = ""
      }
      if (start) {
        cont = (line ~ /\\[[:space:]]*$/)
        sub(/\\[[:space:]]*$/, "", line)
        cmd = (cmd == "" ? line : cmd " " line)
        if (!cont) flush()
      }
    }
    END { flush() }' "$1"
}

# Every workflow and every script under scripts/ (the shell, PowerShell and Python ones).
scanned=()
for f in .github/workflows/*.yml .github/workflows/*.yaml; do
  if [[ -f "$f" ]]; then
    scanned+=("$f")
  fi
done
if [[ -d scripts ]]; then
  while IFS= read -r f; do
    scanned+=("$f")
  done < <(find scripts -type f \( -name '*.sh' -o -name '*.bash' -o -name '*.ps1' -o -name '*.psm1' -o -name '*.py' \) | sort)
fi

for f in ${scanned[@]+"${scanned[@]}"}; do
  while IFS=$'\t' read -r line cmd; do
    [[ -n "$line" ]] || continue
    named="$( { grep -o -- '-DQOD_NODE_SEARCH=' <<<"$cmd" || true; } | wc -l | tr -d ' ')"
    value=""
    if [[ "$cmd" =~ -DQOD_NODE_SEARCH=([A-Za-z0-9]+) ]]; then
      value="${BASH_REMATCH[1]}"
    fi
    dir=""
    if [[ "$cmd" =~ -B[[:space:]]*([^[:space:]]+) ]]; then
      dir="${BASH_REMATCH[1]##*/}"
      dir="${dir//[\"\']/}"
    fi
    if [[ "$named" -gt 1 ]]; then
      search_problems+=("$f:$line configures qod-node naming QOD_NODE_SEARCH $named times: the last one wins in CMake, so a shipped build names it once, OFF.")
    elif [[ -z "$value" ]]; then
      search_problems+=("$f:$line configures qod-node without naming QOD_NODE_SEARCH: a shipped build names -DQOD_NODE_SEARCH=OFF.")
    elif [[ "$value" != OFF ]]; then
      if [[ "$f" == scripts/release/refresh-qod-node-binaries.sh ]]; then
        search_problems+=("$f:$line configures qod-node with QOD_NODE_SEARCH=$value: every binary it vendors ships, so it names OFF.")
      elif [[ "$dir" != build-search* ]]; then
        search_problems+=("$f:$line configures qod-node with QOD_NODE_SEARCH=$value into '$dir': only a build-search* development tree builds it ON.")
      fi
    fi
    # The vendored binaries, macOS ones included, come from a build-release tree (CI and the refresh script): its
    # configure writes the macOS floor the shipped osx binaries record (OWNER RULING 2026-11-17); Linux ignores it.
    if [[ "$dir" == build-release && ! "$cmd" =~ -DCMAKE_OSX_DEPLOYMENT_TARGET=15\.0([[:space:]]|$) ]]; then
      search_problems+=("$f:$line configures the shipped build-release tree without -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0, the macOS floor its binaries record.")
    fi
    if [[ "$cmd" =~ -DQOD_SEARCH_TEST_HOOKS=ON && "$dir" != build-search-hooks* ]]; then
      search_problems+=("$f:$line configures the library's test hooks into '$dir': only a build-search-hooks* tree builds them.")
    fi
  done < <(qod_node_configures "$f")
done

# No uploaded artifact comes from a development tree.
if [[ -f .github/workflows/qod-node.yml ]]; then
  while IFS= read -r hit; do
    search_problems+=(".github/workflows/qod-node.yml:${hit%%:*} uploads ${hit#*:} from a build-search* development tree.")
  done < <(awk '
    /uses:[[:space:]]*actions\/upload-artifact/ { match($0, /^[[:space:]]*/); indent = RLENGTH; in_upload = 1; next }
    in_upload && /^[[:space:]]*$/ { next }
    in_upload { match($0, /^[[:space:]]*/); if (RLENGTH <= indent) in_upload = 0 }
    in_upload && !/^[[:space:]]*#/ && /build-search/ { line = $0; sub(/^[[:space:]]+/, "", line); print NR ":" line }' \
    .github/workflows/qod-node.yml)
fi

# The publishing steps name the setting they ship: OFF.
for spec in ".github/workflows/release.yml:QOD_NODE_SEARCH:" ".github/workflows/qod-node.yml:QOD_NODE_SEARCH:" \
            "docker/quack-node/Dockerfile:ARG QOD_NODE_SEARCH="; do
  f="${spec%%:*}"
  key="${spec#*:}"
  [[ -f "$f" ]] || continue
  found=0
  while IFS= read -r hit; do
    found=1
    value="${hit#*"$key"}"
    value="$(echo "$value" | tr -d "\"' ")"
    [[ "$value" == OFF ]] \
      || search_problems+=("$f:${hit%%:*} ships QOD_NODE_SEARCH=$value: shipped binaries name OFF.")
  done < <(grep -nE "^[[:space:]]*${key}" "$f" | sed -E "s/^([0-9]+):[[:space:]]*/\1:/" || true)
  [[ "$found" == 1 ]] || search_problems+=("$f names no ${key%[:=]} for what it publishes: it ships QOD_NODE_SEARCH=OFF.")
done

# A configure that names nothing still builds without search.
if [[ -f native/qod-node/CMakeLists.txt ]] \
   && ! grep -qE '^option\(QOD_NODE_SEARCH "[^"]*" OFF\)' native/qod-node/CMakeLists.txt; then
  search_problems+=("native/qod-node/CMakeLists.txt: QOD_NODE_SEARCH does not default to OFF.")
fi

status=0
if [[ ${#problems[@]} -gt 0 ]]; then
  echo "release-pin guard: this tree cannot build outside a developer machine:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  echo "The release-day steps: scripts/release/RELEASE-DAY-DUCKDB-2.0.md" >&2
  [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::error::release-pin guard failed: pre-release DuckDB JDBC or mavenLocal resolver (see log)"
  status=1
fi
if [[ ${#search_problems[@]} -gt 0 ]]; then
  echo "release-pin guard: a shipped qod-node could link the search library (OWNER RULING 2026-11-16, D2):" >&2
  for p in "${search_problems[@]}"; do echo "  - $p" >&2; done
  [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::error::release-pin guard failed: a shipped configure does not name QOD_NODE_SEARCH=OFF (see log)"
  status=1
fi
[[ "$status" == 0 ]] || exit 1
echo "release-pin guard OK: no pre-release JDBC, no mavenLocal resolver, every shipped qod-node configure names QOD_NODE_SEARCH=OFF"
