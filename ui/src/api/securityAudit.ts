import type { AuditStatus, SecurityAuditResponse, SecurityFinding } from './types';

export const AREA_ORDER: SecurityFinding['area'][] = ['network', 'secrets', 'identity', 'data', 'fleet'];

export const AREA_LABEL: Record<SecurityFinding['area'], string> = {
  network: 'Network and TLS', secrets: 'Secrets', identity: 'Identity', data: 'Nodes and data', fleet: 'Fleet',
};

/** The badge class of each status (styles.css .badge variants). */
export const STATUS_BADGE: Record<AuditStatus, string> = {
  critical: 'badge bad', warn: 'badge warn', error: 'badge', ok: 'badge good', na: 'badge',
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

export function groupByArea(findings: SecurityFinding[]): { area: SecurityFinding['area']; findings: SecurityFinding[] }[] {
  return AREA_ORDER
    .map(area => ({ area, findings: findings.filter(f => f.area === area) }))
    .filter(g => g.findings.length > 0);
}
