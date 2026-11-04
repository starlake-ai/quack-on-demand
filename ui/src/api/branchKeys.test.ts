import { describe, expect, it } from 'vitest';
import { parseBranchKeys } from './branchKeys';

describe('parseBranchKeys', () => {
  it('reads one key per line, ignoring blank lines', () => {
    expect(parseBranchKeys('sales.orders=order_id\n\n sales.items = item_id, line \n')).toEqual({
      keys: { 'sales.orders': ['order_id'], 'sales.items': ['item_id', 'line'] },
    });
  });

  it('reads nothing as no keys', () => {
    expect(parseBranchKeys('  \n')).toEqual({ keys: {} });
  });

  it('splits a line at its first =, as the CLI does, so a column name may hold one', () => {
    expect(parseBranchKeys('sales.orders=a=b')).toEqual({ keys: { 'sales.orders': ['a=b'] } });
  });

  it('refuses a line it cannot read, naming it', () => {
    for (const bad of ['orders=id', 'sales.orders', 'sales.orders=', 'sales.orders= , ']) {
      const out = parseBranchKeys(bad);
      expect('error' in out && out.error).toContain(bad.trim());
    }
  });

  it('refuses a table named twice, rather than keep the last and widen its key', () => {
    const out = parseBranchKeys('sales.orders=a\n sales.orders =b');
    expect('error' in out && out.error).toContain('sales.orders');
  });
});
