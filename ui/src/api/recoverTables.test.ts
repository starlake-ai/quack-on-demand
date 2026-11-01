import { describe, expect, it } from 'vitest';
import { databasesOf, undropRequest } from './recoverTables';

describe('databasesOf', () => {
  it('lists each database of the pools once, sorted', () => {
    expect(databasesOf([
      { tenant: 'acme', tenantDb: 'lake', pool: 'bi', status: 'running' },
      { tenant: 'acme', tenantDb: 'files', pool: 'etl', status: 'running' },
      { tenant: 'acme', tenantDb: 'lake', pool: 'etl', status: 'suspended' },
    ])).toEqual(['files', 'lake']);
    expect(databasesOf([])).toEqual([]);
  });
});

describe('undropRequest', () => {
  it('leaves a blank new name out and trims a given one', () => {
    expect(undropRequest('lake', 'main', 'orders', '  ', 7)).toEqual({
      tenantDb: 'lake', schema: 'main', table: 'orders', tableId: 7,
    });
    expect(undropRequest('lake', 'main', 'orders', ' orders_back ', 7)).toEqual({
      tenantDb: 'lake', schema: 'main', table: 'orders', asName: 'orders_back', tableId: 7,
    });
  });

  it('names the listed drop, so an older drop of a name is the one recovered', () => {
    expect(undropRequest('lake', 'main', 'orders', '', 3).tableId).toBe(3);
  });
});
