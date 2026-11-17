import { describe, expect, it } from 'vitest';
import type { PatToolEntry } from './types';
import { missesRest, pickedIn, replaceGroup, toolLabel, toolOptions, toolsRequest } from './patTools';

const entry = (name: string, group: PatToolEntry['group'], label = name): PatToolEntry => ({
  name, label, group, readOnly: true, description: `about ${name}`,
});

// In the server's order: data, then admin, then surfaces, each by name.
const CATALOG: PatToolEntry[] = [
  entry('list_tables', 'data'),
  entry('run_sql', 'data'),
  entry('create_tenant', 'admin'),
  entry('list_users', 'admin'),
  entry('rest', 'surface', 'REST data edge'),
];

describe('toolLabel', () => {
  it('shows an MCP tool by its name and a surface as label (name)', () => {
    expect(toolLabel(CATALOG[1])).toBe('run_sql');
    expect(toolLabel(CATALOG[4])).toBe('REST data edge (rest)');
  });
});

describe('toolOptions', () => {
  it('keeps one group, in the server order, with the description as hover text', () => {
    expect(toolOptions(CATALOG, 'data')).toEqual([
      { value: 'list_tables', label: 'list_tables', title: 'about list_tables' },
      { value: 'run_sql', label: 'run_sql', title: 'about run_sql' },
    ]);
    expect(toolOptions(CATALOG, 'surface').map(o => o.label)).toEqual(['REST data edge (rest)']);
  });
});

describe('pickedIn and replaceGroup', () => {
  it('reads and replaces one group without touching the others', () => {
    const picked = ['rest', 'run_sql', 'list_users'];
    expect(pickedIn(CATALOG, 'data', picked)).toEqual(['run_sql']);
    expect(pickedIn(CATALOG, 'admin', picked)).toEqual(['list_users']);
    const next = replaceGroup(CATALOG, 'data', picked, ['list_tables']);
    expect([...next].sort()).toEqual(['list_tables', 'list_users', 'rest']);
    expect(replaceGroup(CATALOG, 'surface', picked, [])).toEqual(['run_sql', 'list_users']);
  });
});

describe('toolsRequest', () => {
  it('omits the axis when nothing is picked, never sends an empty list', () => {
    expect(toolsRequest([])).toBeUndefined();
    expect(toolsRequest(['run_sql', 'rest'])).toEqual(['run_sql', 'rest']);
  });
});

describe('missesRest', () => {
  it('notes a token narrowed to MCP tools without rest', () => {
    expect(missesRest(CATALOG, ['run_sql'])).toBe(true);
    expect(missesRest(CATALOG, ['create_tenant'])).toBe(true);
  });

  it('says nothing when rest is picked, nothing is picked, or only surfaces are', () => {
    expect(missesRest(CATALOG, ['run_sql', 'rest'])).toBe(false);
    expect(missesRest(CATALOG, [])).toBe(false);
    expect(missesRest(CATALOG, ['rest'])).toBe(false);
  });
});
