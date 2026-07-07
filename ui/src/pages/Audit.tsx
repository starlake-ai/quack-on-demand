import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { AuditEventEntry, TenantResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';

const FAMILIES = ['control-plane', 'auth', 'data-denial', 'data-write'];
const NO_TENANT = '__none__'; // client-side sentinel; never sent as ?tenant=

function OutcomeBadge({ outcome }: { outcome: string }) {
  const cls = outcome === 'ok' ? 'good' : outcome === 'denied' ? 'warn' : 'bad';
  return <span className={`badge ${cls}`}>{outcome}</span>;
}

export default function Audit() {
  const { superuser } = useAuth();
  const [telemetryEnabled, setTelemetryEnabled] = useState(false);
  const [events, setEvents] = useState<AuditEventEntry[]>([]);
  const [nextBefore, setNextBefore] = useState<string | null>(null);
  const [family, setFamily] = useState('');
  const [tenant, setTenant] = useState('');
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [err, setErr] = useState('');

  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [actions, setActions] = useState<string[]>([]);

  useEffect(() => {
    api.clientConfig()
      .then(cfg => setTelemetryEnabled(cfg.telemetryEnabled !== false))
      .catch(() => setTelemetryEnabled(false));
  }, []);

  useEffect(() => {
    api.auditActions().then(r => setActions(r.actions)).catch(() => setActions([]));
  }, []);

  useEffect(() => {
    if (superuser) {
      api.listTenants().then(r => setTenants(r.tenants)).catch(() => setTenants([]));
    }
  }, [superuser]);

  const params = useCallback((before?: string) => {
    const p: Record<string, string> = { limit: '50' };
    if (family) p.family = family;
    if (tenant === NO_TENANT) p.noTenant = 'true';
    else if (tenant) p.tenant = tenant;
    if (actor) p.actor = actor;
    if (action) p.action = action;
    if (from) p.from = new Date(from).toISOString();
    if (to) p.to = new Date(to).toISOString();
    if (before) p.before = before;
    return p;
  }, [family, tenant, actor, action, from, to]);

  const load = useCallback((append: boolean, before?: string) => {
    api.auditList(params(before))
      .then(r => {
        setEvents(prev => (append ? [...prev, ...r.events] : r.events));
        setNextBefore(r.events.length === 50 ? r.nextBefore : null);
        setErr('');
      })
      .catch(e => setErr(String(e)));
  }, [params]);

  useEffect(() => { if (telemetryEnabled) load(false); }, [telemetryEnabled, load]);

  if (!telemetryEnabled) {
    return (
      <>
        <h2>Control Plane</h2>
        <p>Telemetry is disabled (telemetry.store = none). No audit events are recorded.</p>
      </>
    );
  }

  return (
    <>
      <h2>Control Plane</h2>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
        <select value={family} onChange={e => setFamily(e.target.value)}>
          <option value="">all families</option>
          {FAMILIES.map(f => <option key={f} value={f}>{f}</option>)}
        </select>
        {superuser && (
          <select value={tenant} onChange={e => setTenant(e.target.value)}>
            <option value="">all tenants</option>
            <option value={NO_TENANT}>(no tenant)</option>
            {tenants.map(t => (
              <option key={t.id} value={t.id}>
                {t.displayName === t.id ? t.id : `${t.displayName} (${t.id})`}
              </option>
            ))}
          </select>
        )}
        <input placeholder="actor" value={actor} onChange={e => setActor(e.target.value)} />
        <select value={action} onChange={e => setAction(e.target.value)}>
          <option value="">all actions</option>
          {actions.map(a => <option key={a} value={a}>{a}</option>)}
        </select>
        <input type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} />
        <input type="datetime-local" value={to} onChange={e => setTo(e.target.value)} />
        <button type="button" className="copy-btn" onClick={() => load(false)}>Refresh</button>
      </div>
      {err && <p className="login-err">{err}</p>}
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Family</th>
            <th>Actor</th>
            <th>Action</th>
            <th>Target</th>
            <th>Tenant</th>
            <th>Outcome</th>
          </tr>
        </thead>
        <tbody>
          {events.map(e => {
            const isOpen = expanded === e.id;
            return (
              <tr key={e.id} onClick={() => setExpanded(isOpen ? null : e.id)} style={{ cursor: 'pointer' }}>
                <td>{new Date(e.ts).toLocaleString()}</td>
                <td><span className="badge">{e.family}</span></td>
                <td>{e.actor}{e.actorRealm === 'system' ? ' (system)' : ''}</td>
                <td>{e.action}</td>
                <td>{e.target ?? ''}</td>
                <td>{e.tenant ?? ''}</td>
                <td>
                  <OutcomeBadge outcome={e.outcome} />
                  {isOpen && Object.keys(e.detail).length > 0 && (
                    <dl style={{ margin: '6px 0 0', fontSize: '0.85em' }}>
                      {Object.entries(e.detail).map(([k, v]) => (
                        <div key={k}>
                          <dt style={{ display: 'inline', fontWeight: 600 }}>{k}: </dt>
                          <dd style={{ display: 'inline', margin: 0, whiteSpace: 'pre-wrap' }}>{v}</dd>
                        </div>
                      ))}
                    </dl>
                  )}
                </td>
              </tr>
            );
          })}
          {events.length === 0 && (
            <tr><td colSpan={7}>No audit events match the filter.</td></tr>
          )}
        </tbody>
      </table>
      {nextBefore && (
        <button type="button" className="copy-btn" style={{ marginTop: 8 }} onClick={() => load(true, nextBefore)}>
          Load more
        </button>
      )}
    </>
  );
}
