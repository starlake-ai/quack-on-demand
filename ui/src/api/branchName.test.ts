import { describe, expect, it } from 'vitest';
import { BranchNamePattern } from './branchName';

// As a browser compiles an input's `pattern`: anchored, with the `v` flag.
const asBrowser = () => new RegExp(`^(?:${BranchNamePattern})$`, 'v');

describe('BranchNamePattern', () => {
  it('compiles the way a browser compiles it, or the browser ignores it and says so only in the console', () => {
    expect(asBrowser).not.toThrow();
  });

  it('takes what the server takes', () => {
    for (const good of ['a', 'feature-x', 'a_b-c9', 'z'.repeat(48)]) {
      expect(asBrowser().test(good), good).toBe(true);
    }
  });

  it('refuses what the server refuses', () => {
    for (const bad of ['', 'Feature', 'Bad Name', '9a', '-a', '_a', 'a.b', 'a b', 'z'.repeat(49)]) {
      expect(asBrowser().test(bad), bad).toBe(false);
    }
  });
});
