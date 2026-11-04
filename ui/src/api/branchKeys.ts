/** The proposal form's key lines: `schema.table=column[,column...]`, one per line. */
export function parseBranchKeys(
  text: string,
): { keys: Record<string, string[]> } | { error: string } {
  const keys: Record<string, string[]> = {};
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (line === '') continue;
    const eq = line.indexOf('=');
    const table = eq < 0 ? '' : line.slice(0, eq).trim();
    const columns = eq < 0 ? [] : line.slice(eq + 1).split(',').map(c => c.trim()).filter(c => c !== '');
    if (!table.includes('.') || columns.length === 0) {
      return { error: `'${line}' must read schema.table=column[,column...]` };
    }
    if (keys[table] !== undefined) {
      return { error: `'${table}' is named twice; give all its columns on one line` };
    }
    keys[table] = columns;
  }
  return { keys };
}
