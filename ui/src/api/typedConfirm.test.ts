import { describe, expect, it } from 'vitest';
import { typedNameMatches } from './typedConfirm';

describe('typedNameMatches', () => {
  it('takes the exact name, surrounding spaces aside', () => {
    expect(typedNameMatches('acme_tpch', 'acme_tpch')).toBe(true);
    expect(typedNameMatches('  acme_tpch ', 'acme_tpch')).toBe(true);
  });

  it('refuses anything else', () => {
    for (const typed of ['', 'y', 'yes', 'ACME_TPCH', 'acme_tpc', 'acme_tpch2', 'acme tpch']) {
      expect(typedNameMatches(typed, 'acme_tpch')).toBe(false);
    }
  });

  it('never matches an empty name', () => {
    expect(typedNameMatches('', '')).toBe(false);
    expect(typedNameMatches('  ', '')).toBe(false);
  });
});
