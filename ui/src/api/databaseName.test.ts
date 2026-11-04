import { describe, expect, it } from 'vitest';
import { databaseSuffixError } from './databaseName';

describe('databaseSuffixError', () => {
  it('takes what the server takes', () => {
    for (const good of ['prod', 'eu', 'tpch1', 'a_b', 'a1_b2_c3', 'Sales']) {
      expect(databaseSuffixError(good), good).toBeNull();
    }
  });

  it('asks for a suffix when there is none', () => {
    expect(databaseSuffixError('')).toContain('required');
  });

  it('refuses what the form does not take, saying what a name may hold', () => {
    for (const bad of ['a__b', 'a_', '_a', '__br_x', '1a', 'a-b', 'a b', 'a.b']) {
      expect(databaseSuffixError(bad), bad).toContain('single underscores');
    }
  });
});
