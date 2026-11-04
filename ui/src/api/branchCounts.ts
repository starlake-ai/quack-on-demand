import { ApiError } from './client';
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
    ? conflictPolicyText(onConflict)
    : 'Any conflict refuses the merge: propose again with a conflict policy.';
}

/** What a proposal's policy does to every kind of conflict. An approver with no grant on a table sees
  * no numbers, so this can be all they read. Only no policy at all reads as a refusal: a name this
  * console does not know (a policy the server gained later) is named, never read as one. */
export function conflictPolicyText(onConflict?: string | null): string {
  if (onConflict === 'main') {
    return 'Main wins every row conflict: a conflicting cell keeps its value, a row it deleted stays ' +
      'deleted, a row it updated stays even if the branch deleted it, and a branch row matching a ' +
      'key is dropped.';
  }
  if (onConflict === 'branch') {
    return 'The branch wins every row conflict: a conflicting cell takes its value, a row it deleted is ' +
      'deleted from main even if main updated it, a row it updated returns even if main deleted ' +
      "it, and main's rows matching a key are deleted.";
  }
  if (!onConflict) return 'Any conflict refuses the merge.';
  return `Conflicts follow the policy '${onConflict}'.`;
}

/** The change type a diff read asks for. A change of the filter names its choice, since the state
  * it sets reaches `onScreen` only on the next render, and `''` is its choice of every type. A read
  * that names none (Load more) follows the filter on screen. */
export function diffChangeType(named: string | undefined, onScreen: string): string | undefined {
  return (named ?? onScreen) || undefined;
}

/** What a failed branch action reads as. An answer is the server's own text. With no answer (the
  * connection dropped, or a proxy or the browser gave up, which a merge of hours invites) the action
  * may still be running or may have gone through, so a bare `Failed to fetch`, or a gateway's error
  * page, would read as a failure that did not happen. A 5xx with no error code never came from the
  * handlers. */
export function actionErrorText(e: unknown): string {
  if (e instanceof ApiError && (e.code !== undefined || e.status < 500)) return e.message;
  const cause = e instanceof ApiError ? `HTTP ${e.status}` : e instanceof Error ? e.message : String(e);
  return `no answer from the server (${cause}). It may still be running, or may have gone through: ` +
    'Refresh shows where it stands.';
}

/** The note for a merge refused because its proposal has merged, in the server's words, and `null`
  * for anything else. What a retry reads after an answer that was lost, and what a second approver
  * reads: the merge is done, so it is no error. Decided by the code, never by the words. */
export function alreadyMergedNote(e: unknown): string | null {
  return e instanceof ApiError && e.code === 'already_merged' ? e.message : null;
}

/** The note after a merge request's status chip, for a request that carries an error. On an open
  * proposal an error starting `timeout: ` is no failure: a send was made and has not answered, so a
  * merge is running or may have landed. The server tests the prefix and never the word, since an
  * engine error can hold it too (a lock timeout). */
export function proposalErrorNote(status: string, error: string): string {
  return status === 'proposed' && error.startsWith('timeout: ')
    ? '(a merge is running, or may have landed: merge again, which settles it)'
    : '(error)';
}
