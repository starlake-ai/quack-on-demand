import { describe, expect, it } from 'vitest';
import { ageOf, unhealthyTitle } from './NodeHealthBadge';

const now = Date.parse('2026-11-12T10:00:00Z');

describe('ageOf', () => {
  it('reads seconds, minutes, hours and days', () => {
    expect(ageOf('2026-11-12T09:59:20Z', now)).toBe('40s');
    expect(ageOf('2026-11-12T09:57:00Z', now)).toBe('3m');
    expect(ageOf('2026-11-12T08:00:00Z', now)).toBe('2h');
    expect(ageOf('2026-11-07T10:00:00Z', now)).toBe('5d');
  });

  it('is empty for nothing or an unparsable time', () => {
    expect(ageOf(undefined, now)).toBe('');
    expect(ageOf('not a time', now)).toBe('');
  });
});

describe('unhealthyTitle', () => {
  it('gives the manager line, since when and the last probe', () => {
    const title = unhealthyTitle(
      {
        healthy: false,
        draining: false,
        quarantined: false,
        unhealthyReason: 'unreachable',
        unhealthyDetail: 'no connection to 127.0.0.1:23901: nothing answers there',
        unhealthySince: '2026-11-12T09:57:00Z',
        lastProbeAt: '2026-11-12T09:59:55Z',
      },
      now,
    );
    expect(title.split('\n')).toEqual([
      'no connection to 127.0.0.1:23901: nothing answers there',
      'unhealthy since 2026-11-12T09:57:00Z (3m ago)',
      'last health probe 5s ago',
    ]);
  });

  it('says the checks failed when no reason was recorded', () => {
    expect(unhealthyTitle({ healthy: false, draining: false, quarantined: false }, now)).toBe(
      'the node failed its health checks',
    );
  });
});
