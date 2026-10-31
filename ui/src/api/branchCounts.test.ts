import { describe, expect, it } from 'vitest';
import { branchCount, CountsUnavailableTitle } from './branchCounts';

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
