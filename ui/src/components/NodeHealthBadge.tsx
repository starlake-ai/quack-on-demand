import type { NodeInfo } from '../api/types';

type Health = Pick<
  NodeInfo,
  'healthy' | 'draining' | 'quarantined' | 'unhealthyReason' | 'unhealthyDetail' | 'unhealthySince' | 'lastProbeAt'
>;

/** How long ago `iso` was, coarsely: "40s", "3m", "2h", "5d". Empty when it does not parse. */
export function ageOf(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

/** The hover text of an unhealthy node: the manager's line, since when, and the last probe. */
export function unhealthyTitle(n: Health, now: number = Date.now()): string {
  const lines = [n.unhealthyDetail ?? 'the node failed its health checks'];
  if (n.unhealthySince) lines.push(`unhealthy since ${n.unhealthySince} (${ageOf(n.unhealthySince, now)} ago)`);
  if (n.lastProbeAt) lines.push(`last health probe ${ageOf(n.lastProbeAt, now)} ago`);
  return lines.join('\n');
}

/** A node's health badge. An unhealthy node shows why (the manager's reason code) and for how
 *  long, the full line on hover; `showDetail` also prints that line under the badge. */
export function NodeHealthBadge({ node, showDetail = false }: { node: Health; showDetail?: boolean }) {
  if (node.quarantined) return <span className="badge warn">quarantined</span>;
  if (node.draining) return <span className="badge warn">draining</span>;
  if (node.healthy) return <span className="badge good">healthy</span>;
  const age = ageOf(node.unhealthySince);
  const label = ['unhealthy', node.unhealthyReason?.replace(/_/g, ' '), age].filter(Boolean).join(' · ');
  return (
    <span>
      <span className="badge bad" title={unhealthyTitle(node)} style={{ cursor: 'help' }}>{label}</span>
      {showDetail && node.unhealthyDetail && (
        <div className="muted" style={{ fontSize: 12, marginTop: 4, maxWidth: 360, whiteSpace: 'normal' }}>
          {node.unhealthyDetail}
        </div>
      )}
    </span>
  );
}
