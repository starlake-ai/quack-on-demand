import { describe, expect, it } from 'vitest';
import type { SecurityFinding } from './types';
import { STATUS_BADGE, areaLabel, groupByArea, summaryLine, visibleFindings } from './securityAudit';

const f = (id: string, status: SecurityFinding['status'], area: string = 'network'): SecurityFinding =>
  ({ id, area, status, title: id, detail: '', fix: '', settings: [], docs: 'https://docs.starlake.ai/qod' });

describe('securityAudit helpers', () => {
  it('summarizes counts, leaving out zeros', () => {
    expect(summaryLine({ critical: 2, warn: 5, ok: 15, na: 0, error: 0 })).toBe('2 critical, 5 warnings, 15 ok');
    expect(summaryLine({ critical: 0, warn: 1, ok: 0, na: 3, error: 1 })).toBe('1 warning, 1 error, 3 n/a');
  });

  it('hides ok and n/a unless asked', () => {
    const all = [f('a', 'critical'), f('b', 'ok'), f('c', 'na'), f('d', 'error')];
    expect(visibleFindings(all, false).map(x => x.id)).toEqual(['a', 'd']);
    expect(visibleFindings(all, true).map(x => x.id)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('groups by area in a fixed order, keeping the server order inside an area', () => {
    const got = groupByArea([f('x', 'warn', 'fleet'), f('y', 'critical', 'network'), f('z', 'ok', 'network')]);
    expect(got.map(g => g.area)).toEqual(['network', 'fleet']);
    expect(got[0].findings.map(x => x.id)).toEqual(['y', 'z']);
  });

  it('keeps an area it does not know, last, in the order the server sent', () => {
    const got = groupByArea([
      f('a', 'warn', 'quotas'), f('b', 'ok', 'network'), f('c', 'critical', 'billing'), f('d', 'ok', 'quotas'),
    ]);
    expect(got.map(g => g.area)).toEqual(['network', 'quotas', 'billing']);
    expect(got[1].findings.map(x => x.id)).toEqual(['a', 'd']);
    expect(got.flatMap(g => g.findings).length).toBe(4);
  });

  it('labels a known area and shows an unknown one under its raw name', () => {
    expect(areaLabel('data')).toBe('Nodes and data');
    expect(areaLabel('quotas')).toBe('quotas');
  });

  it('gives error a badge of its own, distinct from n/a', () => {
    expect(STATUS_BADGE.error).not.toBe(STATUS_BADGE.na);
    expect(STATUS_BADGE.error).toBe('badge error');
    expect(new Set(Object.values(STATUS_BADGE)).size).toBe(Object.keys(STATUS_BADGE).length);
  });
});
