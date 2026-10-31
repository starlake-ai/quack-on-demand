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
