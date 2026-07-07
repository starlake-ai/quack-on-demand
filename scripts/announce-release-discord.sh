#!/usr/bin/env bash
#
# Announce a release on the Discord #news channel: repo + release-notes links
# followed by the version's CHANGELOG.md section, split into messages under
# Discord's 2000-character limit.
#
# Called by release-jar.sh right after it creates the GitHub release. Also safe
# to run standalone to (re)announce a version:
#   ./scripts/announce-release-discord.sh 0.3.6
#
# Webhook: QOD_DISCORD_WEBHOOK_URL env var, falling back to a
# QOD_DISCORD_WEBHOOK_URL=... line in the untracked .env. The URL is a
# credential (anyone holding it can post to the channel) - never commit it.

set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

version="${1:-}"
[[ -n "$version" ]] || { echo "usage: $0 <version>   (e.g. 0.3.6)" >&2; exit 1; }
version="${version#v}"

webhook="${QOD_DISCORD_WEBHOOK_URL:-}"
if [[ -z "$webhook" && -f "$REPO_DIR/.env" ]]; then
  webhook="$(sed -n 's/^QOD_DISCORD_WEBHOOK_URL=//p' "$REPO_DIR/.env" | tail -1)"
fi
[[ -n "$webhook" ]] \
  || { echo "ERROR: QOD_DISCORD_WEBHOOK_URL not set (env var or .env line)." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 not on PATH." >&2; exit 1; }

# The section body between "## <version>" and the next "## " heading.
section="$(awk -v v="$version" '
  $0 == "## " v { found = 1; next }
  found && /^## / { exit }
  found { print }
' "$REPO_DIR/CHANGELOG.md")"
# Non-blank check via grep, NOT ${section//[[:space:]]/}: macOS /bin/bash 3.2
# evaluates that substitution quadratically (minutes of CPU on a ~20KB section).
grep -q '[^[:space:]]' <<<"$section" \
  || { echo "ERROR: no '## $version' section in CHANGELOG.md." >&2; exit 1; }

header="**quack-on-demand v${version} released**
https://github.com/starlake-ai/quack-on-demand
Release notes: https://github.com/starlake-ai/quack-on-demand/releases/tag/v${version}"

post() {
  python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read().rstrip()}))' \
    <<<"$1" \
    | curl -fsS -H 'Content-Type: application/json' -d @- "${webhook}?wait=true" >/dev/null
}

# Discord caps a message at 2000 characters; chunk on line boundaries with
# headroom. Sequential posts keep the chunks in channel order.
limit=1900
chunk="$header"$'\n'
while IFS= read -r line; do
  if (( ${#chunk} + ${#line} + 1 > limit )); then
    post "$chunk"
    chunk=""
  fi
  chunk+="$line"$'\n'
done <<<"$section"
if grep -q '[^[:space:]]' <<<"$chunk"; then
  post "$chunk"
fi

echo "announced v${version} on Discord #news."