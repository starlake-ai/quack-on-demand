import { describe, expect, it } from 'vitest';
import {
  branchCount,
  conflictPolicyText,
  CountsUnavailableTitle,
  proposalErrorNote,
  rowConflictsHint,
  rowConflictsText,
} from './branchCounts';
import type { BranchRowConflicts } from './types';

describe('branchCount', () => {
  const row = { inserted: 7, deleted: 1, updated: 2 };

  it('shows a counted table its numbers', () => {
    expect(branchCount({ ...row, countsUnavailable: false }, 'inserted')).toEqual({ text: '7' });
    expect(branchCount(row, 'updated')).toEqual({ text: '2' });
  });

  it('shows a dash, never a 0, when the counts are not available to the viewer', () => {
    const t = { inserted: 0, deleted: 0, updated: 0, countsUnavailable: true };
    for (const f of ['inserted', 'deleted', 'updated'] as const) {
      expect(branchCount(t, f)).toEqual({ text: '-', title: CountsUnavailableTitle });
    }
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
});

// An approver with no grant on a table sees no numbers (design 8.2), so these sentences can be all
// they read before approving: each must say what the policy does to every kind of conflict, and
// not only to cells (a row one side deleted and the other updated, rows matching a key).
describe('conflictPolicyText', () => {
  it('says what main winning does to every kind of conflict', () => {
    expect(conflictPolicyText('main')).toBe(
      'Main wins every conflict: a conflicting cell keeps its value, a row it deleted stays ' +
        'deleted, a row it updated stays even if the branch deleted it, and a branch row matching a ' +
        'key is dropped.',
    );
  });

  it('says what the branch winning does to every kind of conflict', () => {
    expect(conflictPolicyText('branch')).toBe(
      'The branch wins every conflict: a conflicting cell takes its value, a row it deleted is ' +
        'deleted from main even if main updated it, a row it updated returns even if main deleted ' +
        "it, and main's rows matching a key are deleted.",
    );
  });

  it('says that any conflict refuses the merge when the proposal names no policy', () => {
    expect(conflictPolicyText(null)).toBe('Any conflict refuses the merge.');
    expect(conflictPolicyText(undefined)).toBe('Any conflict refuses the merge.');
  });
});

describe('proposalErrorNote', () => {
  it('tells of a merge that may have landed when an open proposal has a send with no answer', () => {
    expect(proposalErrorNote('proposed', 'timeout: no answer from the merge node')).toBe(
      '(a merge is running, or may have landed: merge again, or discard)',
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
