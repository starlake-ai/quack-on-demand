import type { BranchRowConflicts } from './types';

/** A branch change-set count cell. The server flags `countsUnavailable` when the counts were asked
  * for and the node refused the viewer the table (no read grant) or the count failed: its zeros are
  * no counts, so the cell shows a dash with this explanation instead of a 0. */
export const CountsUnavailableTitle =
  'Counts are not available to you: no read grant on this table, or the count failed';

export interface BranchCounts {
  inserted: number;
  deleted: number;
  updated: number;
  countsUnavailable?: boolean;
}

export function branchCount(
  t: BranchCounts,
  field: 'inserted' | 'deleted' | 'updated',
): { text: string; title?: string } {
  return t.countsUnavailable
    ? { text: '-', title: CountsUnavailableTitle }
    : { text: String(t[field]) };
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** One table's row merge, as its proposal saw it. A viewer who may not count the table learns
  * whether it has conflicts, not how many. */
export function rowConflictsText(c: BranchRowConflicts): string {
  if (c.countsUnavailable) {
    return `${c.hasConflicts ? 'has conflicts' : 'no conflicts'}; the numbers are not available to you`;
  }
  const writes = `${c.toUpdate ?? 0} to update, ${c.toDelete ?? 0} to delete, ${c.toInsert ?? 0} to insert`;
  const cells = c.cellConflicts ?? 0;
  const deletes = (c.updateDeleteConflicts ?? 0) + (c.deleteUpdateConflicts ?? 0);
  const inserts = c.keyMatches ?? 0;
  const mainRows = c.mainRowsByKey ?? 0;
  const total = cells + deletes + inserts;
  if (total === 0) return `${writes}; no conflicts`;
  const kinds = [
    cells > 0 ? plural(cells, 'cell', 'cells') : '',
    deletes > 0 ? plural(deletes, 'delete against update', 'deletes against updates') : '',
    inserts > 0
      ? `${plural(inserts, 'double insert', 'double inserts')} (${plural(mainRows, 'row', 'rows')} on main)`
      : '',
  ].filter(k => k !== '');
  return `${writes}; ${plural(total, 'conflict', 'conflicts')}: ${kinds.join(', ')}`;
}

/** What the conflicts mean for a merge, given the open proposal's policy. A change set's `mergeable`
  * reads only table conflicts, so without this a branch with row conflicts reads as ready. */
export function rowConflictsHint(
  tables: { rowConflicts?: BranchRowConflicts | null }[],
  onConflict?: string | null,
): string | null {
  if (!tables.some(t => t.rowConflicts?.hasConflicts)) return null;
  return onConflict
    ? `A conflicting cell takes ${onConflict}'s value.`
    : 'Any conflict refuses the merge: propose again with a conflict policy.';
}
