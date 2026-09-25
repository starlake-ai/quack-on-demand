/** Shared display formatters used across pages. */

/** Human-readable byte count (binary units, one decimal). `null`/`undefined`
  * means the value hasn't been observed yet (e.g. an unscraped node's engine
  * stats, or a fleet server that hasn't reported memory) - rendered as '-'. */
export function fmtBytes(n: number | null | undefined): string {
  if (n == null) return '-';
  if (n < 1024) return `${n} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let v = n;
  let u = -1;
  do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
  return `${v.toFixed(1)} ${units[u]}`;
}
