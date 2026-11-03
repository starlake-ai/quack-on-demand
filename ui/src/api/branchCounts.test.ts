import { describe, expect, it } from 'vitest';
import { branchCount, CountsUnavailableTitle, rowConflictsHint, rowConflictsText } from './branchCounts';
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
    expect(rowConflictsHint([conflicted], 'branch')).toBe("A conflicting cell takes branch's value.");
  });

  it('names whichever side the proposal chose', () => {
    expect(rowConflictsHint([conflicted], 'main')).toBe("A conflicting cell takes main's value.");
  });
});
