import { describe, expect, it } from 'vitest';
import { ApiError } from './client';
import {
  actionErrorText,
  alreadyMergedNote,
  branchCount,
  branchMergeCell,
  conflictPolicyText,
  CountsUnavailableTitle,
  diffChangeType,
  noOpenProposal,
  NotCountedTitle,
  proposalErrorNote,
  rowConflictsHint,
  rowConflictsText,
} from './branchCounts';
import type { BranchRowConflicts } from './types';

describe('branchCount', () => {
  const row = { kind: 'modified', inserted: 7, deleted: 1, updated: 2 };

  it('shows a counted table its numbers', () => {
    expect(branchCount({ ...row, countsUnavailable: false }, 'inserted')).toEqual({ text: '7' });
    expect(branchCount(row, 'updated')).toEqual({ text: '2' });
  });

  it('shows a dash, never a 0, when the counts are not available to the viewer', () => {
    const t = { kind: 'modified', inserted: 0, deleted: 0, updated: 0, countsUnavailable: true };
    for (const f of ['inserted', 'deleted', 'updated'] as const) {
      expect(branchCount(t, f)).toEqual({ text: '-', title: CountsUnavailableTitle });
    }
  });

  it('shows a modified table its zeros: there they are counts', () => {
    expect(branchCount({ kind: 'modified', inserted: 0, deleted: 3, updated: 0 }, 'inserted')).toEqual({ text: '0' });
  });

  it('shows a dash for any kind but modified: the server counts no other, so its zeros are no counts', () => {
    for (const kind of ['created', 'dropped', 'recreated', 'altered', 'a kind this console does not know']) {
      for (const f of ['inserted', 'deleted', 'updated'] as const) {
        expect(branchCount({ kind, inserted: 0, deleted: 0, updated: 0 }, f), kind).toEqual({
          text: '-',
          title: NotCountedTitle,
        });
      }
    }
  });

  it('gives the kind as the reason, whatever else the row says: no grant would change nothing', () => {
    const t = { kind: 'created', inserted: 0, deleted: 0, updated: 0, countsUnavailable: true };
    expect(branchCount(t, 'inserted')).toEqual({ text: '-', title: NotCountedTitle });
  });
});

describe('rowConflictsText', () => {
  const base = {
    schema: 's', table: 't', hasConflicts: false, toUpdate: 3, toDelete: 1, toInsert: 2,
    cellConflicts: 0, updateDeleteConflicts: 0, deleteUpdateConflicts: 0, keyMatches: 0, mainRowsByKey: 0,
    columnConflicts: {}, samples: {},
  };

  it('says what the merge would write', () => {
    expect(rowConflictsText(base)).toBe('3 to update, 1 to delete, 2 to insert; no conflicts');
  });

  it('breaks conflicts down by kind, leaving out the kinds with none', () => {
    expect(
      rowConflictsText({ ...base, hasConflicts: true, cellConflicts: 2, keyMatches: 1, mainRowsByKey: 3 }),
    ).toBe('3 to update, 1 to delete, 2 to insert; 3 conflicts: 2 cells, 1 double insert (3 rows on main)');
  });

  it('shows no numbers to a viewer who may not count the table', () => {
    expect(
      rowConflictsText({ schema: 's', table: 't', hasConflicts: true, countsUnavailable: true }),
    ).toBe('has conflicts; the numbers are not available to you');
    expect(
      rowConflictsText({ schema: 's', table: 't', hasConflicts: false, countsUnavailable: true }),
    ).toBe('no conflicts; the numbers are not available to you');
  });

  it('shows no number to that viewer even when the payload carries some', () => {
    expect(rowConflictsText({ ...base, hasConflicts: true, countsUnavailable: true })).toBe(
      'has conflicts; the numbers are not available to you',
    );
  });

  // What follows the writes in the text: the conflicts alone.
  const conflictsOf = (counts: Partial<BranchRowConflicts>) =>
    rowConflictsText({ ...base, hasConflicts: true, ...counts })
      .replace('3 to update, 1 to delete, 2 to insert; ', '');

  it('names each kind of conflict, in the singular for one and the plural for more', () => {
    expect(conflictsOf({ cellConflicts: 1 })).toBe('1 conflict: 1 cell');
    expect(conflictsOf({ cellConflicts: 2 })).toBe('2 conflicts: 2 cells');
    expect(conflictsOf({ updateDeleteConflicts: 1 })).toBe('1 conflict: 1 delete against update');
    expect(conflictsOf({ deleteUpdateConflicts: 2 })).toBe('2 conflicts: 2 deletes against updates');
    expect(conflictsOf({ keyMatches: 1, mainRowsByKey: 1 })).toBe('1 conflict: 1 double insert (1 row on main)');
    expect(conflictsOf({ keyMatches: 2, mainRowsByKey: 3 })).toBe('2 conflicts: 2 double inserts (3 rows on main)');
  });

  it('counts both ways a delete meets an update as one kind, listed between cells and double inserts', () => {
    expect(
      conflictsOf({
        cellConflicts: 1, updateDeleteConflicts: 1, deleteUpdateConflicts: 1, keyMatches: 1, mainRowsByKey: 2,
      }),
    ).toBe('4 conflicts: 1 cell, 2 deletes against updates, 1 double insert (2 rows on main)');
  });

  it('reads a number the payload leaves out as none, never printing undefined or NaN', () => {
    expect(rowConflictsText({ schema: 's', table: 't', hasConflicts: false })).toBe(
      '0 to update, 0 to delete, 0 to insert; no conflicts',
    );
    expect(rowConflictsText({ schema: 's', table: 't', hasConflicts: true, keyMatches: 1 })).toBe(
      '0 to update, 0 to delete, 0 to insert; 1 conflict: 1 double insert (0 rows on main)',
    );
    expect(rowConflictsText({ schema: 's', table: 't', hasConflicts: true, cellConflicts: 1 })).toBe(
      '0 to update, 0 to delete, 0 to insert; 1 conflict: 1 cell',
    );
  });
});

describe('rowConflictsHint', () => {
  const conflicted = { rowConflicts: { schema: 's', table: 't', hasConflicts: true } };
  const clean = { rowConflicts: { schema: 's', table: 't', hasConflicts: false } };

  it('is silent when no table has conflicts', () => {
    expect(rowConflictsHint([clean, { rowConflicts: null }, {}], undefined)).toBeNull();
  });

  it('warns that a conflict refuses the merge when the proposal names no winner', () => {
    expect(rowConflictsHint([clean, conflicted], null)).toBe(
      'Any conflict refuses the merge: propose again with a conflict policy.',
    );
  });

  it('says which side wins when the proposal names one', () => {
    expect(rowConflictsHint([conflicted], 'branch')).toBe(conflictPolicyText('branch'));
  });

  it('names whichever side the proposal chose', () => {
    expect(rowConflictsHint([conflicted], 'main')).toBe(conflictPolicyText('main'));
  });

  it('names a policy it does not know instead of reading it as a refusal', () => {
    expect(rowConflictsHint([conflicted], 'newest')).toBe("Conflicts follow the policy 'newest'.");
  });
});

// An approver with no grant on a table sees no numbers (design 8.2), so these sentences can be all
// they read before approving: each must say what the policy does to every kind of conflict, and
// not only to cells (a row one side deleted and the other updated, rows matching a key).
describe('conflictPolicyText', () => {
  it('says what main winning does to every kind of conflict', () => {
    expect(conflictPolicyText('main')).toBe(
      'Main wins every row conflict: a conflicting cell keeps its value, a row it deleted stays ' +
        'deleted, a row it updated stays even if the branch deleted it, and a branch row matching a ' +
        'key is dropped.',
    );
  });

  it('says what the branch winning does to every kind of conflict', () => {
    expect(conflictPolicyText('branch')).toBe(
      'The branch wins every row conflict: a conflicting cell takes its value, a row it deleted is ' +
        'deleted from main even if main updated it, a row it updated returns even if main deleted ' +
        "it, and main's rows matching a key are deleted.",
    );
  });

  it('says that any conflict refuses the merge when the proposal names no policy', () => {
    expect(conflictPolicyText(null)).toBe('Any conflict refuses the merge.');
    expect(conflictPolicyText(undefined)).toBe('Any conflict refuses the merge.');
    expect(conflictPolicyText('')).toBe('Any conflict refuses the merge.');
  });

  it('names a policy it does not know, and never reads it as a refusal', () => {
    // The server takes main and branch today. A policy it gains later, shown by a console that
    // predates it, must not read as "refuses": that would be false, and the safe-sounding way.
    expect(conflictPolicyText('newest')).toBe("Conflicts follow the policy 'newest'.");
  });
});

describe('proposalErrorNote', () => {
  it('tells of a merge that may have landed when an open proposal has a send with no answer', () => {
    expect(proposalErrorNote('proposed', 'timeout: no answer from the merge node')).toBe(
      '(a merge is running, or may have landed: merge again, which settles it)',
    );
  });

  it('calls any other error of an open proposal an error, even one that mentions a timeout', () => {
    expect(proposalErrorNote('proposed', 'merge_failed: out of memory')).toBe('(error)');
    // The server tests startsWith, never contains: an engine error can hold the word too, and the
    // prefix is `timeout: ` whole, so an error that merely starts with the word is no timeout.
    expect(proposalErrorNote('proposed', 'lock timeout: try again')).toBe('(error)');
    expect(proposalErrorNote('proposed', 'timeout waiting for the lock')).toBe('(error)');
  });

  it('calls the error of a closed proposal an error, even when it starts like a timeout', () => {
    for (const status of ['failed', 'abandoned', 'merged']) {
      expect(proposalErrorNote(status, 'timeout: no answer from the merge node')).toBe('(error)');
    }
  });
});

describe('diffChangeType', () => {
  it('asks for what the read names, whatever the filter on screen still says', () => {
    expect(diffChangeType('delete', 'insert')).toBe('delete');
  });

  it('takes an empty name as every change type, not as no name', () => {
    expect(diffChangeType('', 'insert')).toBeUndefined();
  });

  it('follows the filter on screen when the read names none, as Load more does', () => {
    expect(diffChangeType(undefined, 'insert')).toBe('insert');
    expect(diffChangeType(undefined, '')).toBeUndefined();
  });
});

describe('actionErrorText', () => {
  it('gives a refusal as the server wrote it, whatever its status', () => {
    expect(actionErrorText(new ApiError(409, 'a merge is running', 'branch_busy'))).toBe('a merge is running');
    expect(actionErrorText(new ApiError(502, 'the merge failed', 'merge_failed'))).toBe('the merge failed');
  });

  it('gives an answer with no code as it came, below the 5xx', () => {
    expect(actionErrorText(new ApiError(401, 'Unauthorized'))).toBe('Unauthorized');
  });

  it('says an action with no answer may still be running, never only that a fetch failed', () => {
    expect(actionErrorText(new TypeError('Failed to fetch'))).toBe(
      'no answer from the server (Failed to fetch). It may still be running, or may have gone ' +
        'through: Refresh shows where it stands.',
    );
  });

  it("reads a gateway's own page the same way, and never shows its HTML", () => {
    const text = actionErrorText(new ApiError(504, '<html><title>504 Gateway Time-out</title></html>'));
    expect(text).toContain('no answer from the server (HTTP 504)');
    expect(text).toContain('may still be running');
    expect(text).not.toContain('<html>');
  });
});

describe('alreadyMergedNote', () => {
  const text = "proposal mg-1 of branch 'b' is already merged: main snapshot 6, tag merge-b-1";

  it("gives the server's text for that one code, as a note: the merge is done", () => {
    expect(alreadyMergedNote(new ApiError(409, text, 'already_merged'))).toBe(text);
  });

  it('leaves every other refusal to the banner', () => {
    for (const code of ['merge_conflict', 'proposal_replaced', 'proposal_unsettled', 'self_merge_forbidden', 'not_found']) {
      expect(alreadyMergedNote(new ApiError(409, 'refused', code)), code).toBeNull();
    }
  });

  it('goes by the code and never by the words', () => {
    expect(alreadyMergedNote(new ApiError(409, 'is already merged', 'merge_conflict'))).toBeNull();
    expect(alreadyMergedNote(new ApiError(409, 'is already merged'))).toBeNull();
  });

  it('leaves an action with no answer to the banner, whatever it says', () => {
    expect(alreadyMergedNote(new TypeError('Failed to fetch'))).toBeNull();
    expect(alreadyMergedNote(new TypeError('already_merged'))).toBeNull();
  });
});

describe('branchMergeCell', () => {
  const table = { schema: 'main', table: 't', mergeable: true };
  const conflict = { schema: 'main', table: 't', reason: 'also created on main since the fork' };

  it('says ok of a table with nothing against it', () => {
    expect(branchMergeCell(table, [])).toEqual({ text: 'ok', refused: false });
  });

  it('says a table main changed too merges cell by cell', () => {
    expect(branchMergeCell({ ...table, threeWay: true }, [])).toEqual({
      text: 'cell by cell, main changed it too',
      refused: false,
    });
  });

  it('says what the list of table conflicts says, never ok', () => {
    expect(branchMergeCell(table, [conflict])).toEqual({
      text: 'cannot be merged: also created on main since the fork',
      refused: true,
    });
  });

  it('goes by the schema and the table of the conflict', () => {
    expect(branchMergeCell(table, [{ ...conflict, table: 'other' }])).toEqual({ text: 'ok', refused: false });
    expect(branchMergeCell(table, [{ ...conflict, schema: 'other' }])).toEqual({ text: 'ok', refused: false });
  });

  it('gives a table refused for itself its own reason, which already says it cannot merge', () => {
    const altered = { ...table, mergeable: false, reason: 'schema change cannot be merged' };
    expect(branchMergeCell(altered, [])).toEqual({ text: 'schema change cannot be merged', refused: true });
    expect(branchMergeCell(altered, [conflict])).toEqual({ text: 'schema change cannot be merged', refused: true });
    expect(branchMergeCell({ ...table, mergeable: false }, [])).toEqual({ text: 'refused', refused: true });
  });
});

describe('noOpenProposal', () => {
  const merged = { status: 'merged', mainSnapshotAfter: 6, tagName: 'merge-b-1' };
  const refused = "merge 'b': it has no open proposal";

  it('says a branch that merged is already merged, as a note: the merge is done', () => {
    expect(noOpenProposal('b', [merged])).toEqual({
      note: "branch 'b' is already merged: main snapshot 6, tag merge-b-1",
    });
  });

  it('finds the row that merged among the proposals before it', () => {
    expect(noOpenProposal('b', [{ status: 'abandoned' }, merged, { status: 'failed' }])).toEqual({
      note: "branch 'b' is already merged: main snapshot 6, tag merge-b-1",
    });
  });

  it('keeps the message for any other state: a proposal a refusal closed, a discard, none at all', () => {
    for (const merges of [[], [{ status: 'failed' }], [{ status: 'abandoned' }], [{ status: 'failed' }, { status: 'abandoned' }]]) {
      expect(noOpenProposal('b', merges), JSON.stringify(merges)).toEqual({ error: refused });
    }
  });

  it('says so all the same when the row holds no snapshot or tag', () => {
    expect(noOpenProposal('b', [{ status: 'merged' }])).toEqual({
      note: "branch 'b' is already merged: main snapshot ?, tag ?",
    });
  });
});
