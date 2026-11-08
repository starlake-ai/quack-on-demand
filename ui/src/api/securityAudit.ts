import type { AuditStatus, SecurityAuditResponse, SecurityFinding } from './types';

/** The areas this console knows, in the order it shows them. */
export type KnownArea = 'network' | 'secrets' | 'identity' | 'data' | 'fleet';

export const AREA_ORDER: KnownArea[] = ['network', 'secrets', 'identity', 'data', 'fleet'];

export const AREA_LABEL: Record<KnownArea, string> = {
  network: 'Network and TLS', secrets: 'Secrets', identity: 'Identity', data: 'Nodes and data', fleet: 'Fleet',
};

/** A known area's label; a newer manager's area under its raw name. */
export function areaLabel(area: string): string {
  return (AREA_LABEL as Record<string, string>)[area] ?? area;
}

/** The badge class of each status (styles.css .badge variants), one per status. */
export const STATUS_BADGE: Record<AuditStatus, string> = {
  critical: 'badge bad', warn: 'badge warn', error: 'badge error', ok: 'badge good', na: 'badge',
};

export const STATUS_LABEL: Record<AuditStatus, string> = {
  critical: 'critical', warn: 'warning', error: 'error', ok: 'ok', na: 'n/a',
};

export function summaryLine(s: SecurityAuditResponse['summary']): string {
  const parts: [number, string, string][] = [
    [s.critical, 'critical', 'critical'], [s.warn, 'warning', 'warnings'], [s.error, 'error', 'errors'],
    [s.ok, 'ok', 'ok'], [s.na, 'n/a', 'n/a'],
  ];
  return parts.filter(([n]) => n > 0).map(([n, one, many]) => `${n} ${n === 1 ? one : many}`).join(', ');
}

export function visibleFindings(all: SecurityFinding[], showPassing: boolean): SecurityFinding[] {
  return showPassing ? all : all.filter(f => f.status !== 'ok' && f.status !== 'na');
}

/** The known areas in their fixed order, then any other area the answer holds, in the order it
 *  first appears, so no finding is dropped. The server's order is kept inside an area. */
export function groupByArea(findings: SecurityFinding[]): { area: string; findings: SecurityFinding[] }[] {
  const known = new Set<string>(AREA_ORDER);
  const others = [...new Set(findings.map(f => f.area).filter(a => !known.has(a)))];
  return [...AREA_ORDER, ...others]
    .map(area => ({ area, findings: findings.filter(f => f.area === area) }))
    .filter(g => g.findings.length > 0);
}
