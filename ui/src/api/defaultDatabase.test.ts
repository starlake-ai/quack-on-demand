import { describe, expect, it } from 'vitest';
import { defaultDatabaseError, ownCatalog } from './defaultDatabase';

describe('ownCatalog', () => {
  it('reads catalogAlias, then dbName, then the name, and memory for kind=memory', () => {
    expect(ownCatalog('ducklake', { catalogAlias: 'sales', dbName: 'acme_lake' }, 'acme_lake')).toBe('sales');
    expect(ownCatalog('duckdb-file', { dbName: 'acme_file' }, 'acme_lake')).toBe('acme_file');
    expect(ownCatalog('ducklake', {}, 'acme_lake')).toBe('acme_lake');
    expect(ownCatalog('memory', undefined, 'acme_mem')).toBe('memory');
  });
});

describe('defaultDatabaseError', () => {
  it('takes nothing, or the database own catalog in any case', () => {
    expect(defaultDatabaseError('', 'ducklake', {}, 'acme_lake')).toBeNull();
    expect(defaultDatabaseError('  ', 'ducklake', {}, 'acme_lake')).toBeNull();
    expect(defaultDatabaseError('ACME_LAKE', 'ducklake', {}, 'acme_lake')).toBeNull();
    expect(defaultDatabaseError('memory', 'memory', {}, 'acme_mem')).toBeNull();
  });

  it('refuses another catalog, naming the one it may name', () => {
    expect(defaultDatabaseError('lake2', 'ducklake', {}, 'acme_lake')).toContain('(acme_lake)');
    expect(defaultDatabaseError('fedpg', 'memory', {}, 'acme_mem')).toContain('(memory)');
  });
});
