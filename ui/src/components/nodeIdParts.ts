// A node id reads quack--<tenant>--<db>--<pool>--<index> (or ends -<hash>-<index> when
// hashed). Split after each `--` so it can wrap between its parts, never inside one:
// every part but the last keeps its trailing `--`, so joining the parts gives the id back.
export function nodeIdParts(id: string): string[] {
  const parts: string[] = [];
  let start = 0;
  for (let i = id.indexOf('--'); i >= 0; i = id.indexOf('--', start)) {
    parts.push(id.slice(start, i + 2));
    start = i + 2;
  }
  if (start < id.length || parts.length === 0) parts.push(id.slice(start));
  return parts;
}
