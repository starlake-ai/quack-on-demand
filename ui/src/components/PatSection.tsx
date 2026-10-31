import { FormEvent, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { PatEntry, PatScope } from '../api/types';

const VERB_CEILINGS = ['RO', 'RW', 'DDL', 'ALL'] as const;

// Comma-separated free text -> trimmed, non-empty array, or undefined when the
// field was left blank. `undefined` (an omitted key) means unrestricted on the
// server; sending `[]` for a blank field would instead mint a token that can
// reach nothing on that axis, so blank must stay omitted.
function parseCsv(raw: string): string[] | undefined {
  const items = raw.split(',').map(s => s.trim()).filter(Boolean);
  return items.length > 0 ? items : undefined;
}

// Compact scope summary for a listing row, e.g. "RO · acme_db · 3 tools".
// An axis the server left unrestricted arrives as JSON `null`, not as a missing
// key: the DTO codec runs under `Configuration.default.withDefaults`, which
// encodes `None` as `null` rather than dropping it. So this must be a loose
// `== null` (matching null AND undefined); a strict `=== undefined` misses the
// real wire shape and throws on `values.length`.
function fmtAxis(values: string[] | null | undefined, noun: string): string | null {
  if (values == null) return null;
  if (values.length === 0) return `no ${noun}s`;
  if (values.length === 1) return values[0];
  return `${values.length} ${noun}s`;
}

function scopeSummary(scope: PatScope | null | undefined): string {
  const parts: string[] = [];
  if (scope?.verbCeiling) parts.push(scope.verbCeiling);
  if (scope?.dropAdmin) parts.push('no admin');
  const database = fmtAxis(scope?.databases, 'database');
  if (database) parts.push(database);
  const pool = fmtAxis(scope?.pools, 'pool');
  if (pool) parts.push(pool);
  const role = fmtAxis(scope?.roles, 'role');
  if (role) parts.push(role);
  const tool = fmtAxis(scope?.tools, 'tool');
  if (tool) parts.push(tool);
  if (scope?.stmtTimeoutMs != null) parts.push(`${scope.stmtTimeoutMs} ms timeout`);
  if (scope?.maxRows != null) parts.push(`${scope.maxRows} row cap`);
  return parts.length > 0 ? parts.join(' · ') : 'unrestricted';
}

// One axis, spelled out for the hover detail. The null-versus-empty distinction is
// load-bearing on the server and invisible in the compact summary: an absent axis
// means unrestricted, an empty array means nothing at all is permitted. A token
// scoped to no tools and one scoped to every tool both render as no entry in the
// summary, so this is where that difference becomes legible.
const DETAIL_MAX = 12;

function axisDetail(values: string[] | null | undefined, label: string): string {
  if (values == null) return `${label}: unrestricted`;
  if (values.length === 0) return `${label}: none`;
  if (values.length <= DETAIL_MAX) return `${label}: ${values.join(', ')}`;
  const shown = values.slice(0, DETAIL_MAX).join(', ');
  return `${label}: ${shown}, +${values.length - DETAIL_MAX} more`;
}

// Full breakdown for the Scope cell's hover, as plain text for a `title`
// attribute. Deliberately lists every axis, including the unrestricted ones, so
// the reader can tell "this axis was never scoped" from "I forgot to look".
function scopeDetail(scope: PatScope | null | undefined): string {
  if (scope == null) return 'Unrestricted on every axis.';
  return [
    `Verb ceiling: ${scope.verbCeiling ?? 'unrestricted'}`,
    `Admin: ${scope.dropAdmin ? 'dropped' : 'inherited from owner'}`,
    axisDetail(scope.databases, 'Databases'),
    axisDetail(scope.pools, 'Pools'),
    axisDetail(scope.roles, 'Roles'),
    axisDetail(scope.tools, 'Tools'),
    `Row cap: ${scope.maxRows != null ? scope.maxRows : 'unrestricted'}`,
    `Statement timeout: ${
      scope.stmtTimeoutMs != null ? `${scope.stmtTimeoutMs} ms` : 'unrestricted'
    }`,
  ].join('\n');
}

function shortTs(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
  } catch { return iso; }
}

function patStatus(p: PatEntry): 'active' | 'revoked' | 'expired' {
  if (p.revoked) return 'revoked';
  if (p.expiresAt && new Date(p.expiresAt) < new Date()) return 'expired';
  return 'active';
}

interface PatForm {
  name: string;
  expiry: string;
  roles: string;
  databases: string;
  pools: string;
  tools: string;
  verbCeiling: string;
  dropAdmin: boolean;
  stmtTimeoutMs: string;
  maxRows: string;
}

const EMPTY_PAT_FORM: PatForm = {
  name: '', expiry: '', roles: '', databases: '', pools: '', tools: '',
  verbCeiling: '', dropAdmin: false, stmtTimeoutMs: '', maxRows: '',
};

/** The caller's personal access tokens, on the profile page: the list first, a create form opened
  * on demand, and a just-created token shown once with a copy action. A token is immutable once
  * minted, so a listed token can only be revoked (active) or deleted from the list (revoked or
  * expired). */
export function PatSection() {
  const [pats, setPats] = useState<PatEntry[] | null>(null);
  const [form, setForm] = useState<PatForm>(EMPTY_PAT_FORM);
  const [creating, setCreating] = useState(false);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [minted, setMinted] = useState<{ name: string; token: string } | null>(null);
  const [copied, setCopied] = useState(false);

  async function load() {
    try {
      setPats((await api.listPats()).tokens);
    } catch (e) {
      setPats([]);
      setErr(errorMessage(e));
    }
  }

  useEffect(() => { void load(); }, []);

  function set<K extends keyof PatForm>(name: K, value: PatForm[K]) {
    setForm(f => ({ ...f, [name]: value }));
  }

  function openCreate() {
    setForm(EMPTY_PAT_FORM);
    setCreating(true);
    setErr('');
    setMinted(null);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr('');
    setCopied(false);
    setBusy(true);
    try {
      const resp = await api.createPat({
        name: form.name.trim(),
        // datetime-local yields a zone-less local timestamp; send it as ISO UTC.
        expiresAt: form.expiry ? new Date(form.expiry).toISOString() : null,
        roles: parseCsv(form.roles),
        databases: parseCsv(form.databases),
        pools: parseCsv(form.pools),
        tools: parseCsv(form.tools),
        verbCeiling: form.verbCeiling ? (form.verbCeiling as (typeof VERB_CEILINGS)[number]) : undefined,
        dropAdmin: form.dropAdmin,
        stmtTimeoutMs: form.stmtTimeoutMs ? Number(form.stmtTimeoutMs) : undefined,
        maxRows: form.maxRows ? Number(form.maxRows) : undefined,
      });
      setMinted({ name: resp.name, token: resp.token });
      setForm(EMPTY_PAT_FORM);
      setCreating(false);
      await load();
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(p: PatEntry) {
    if (!window.confirm(`Revoke token "${p.name}"? Clients using it stop working immediately.`)) return;
    setErr('');
    try {
      await api.revokePat(p.id);
      await load();
    } catch (e) {
      setErr(errorMessage(e));
    }
  }

  async function remove(p: PatEntry) {
    if (!window.confirm(`Delete token "${p.name}" from the list? This cannot be undone.`)) return;
    setErr('');
    try {
      await api.deletePat(p.id);
      await load();
    } catch (e) {
      setErr(errorMessage(e));
    }
  }

  async function copyMinted() {
    if (!minted) return;
    try {
      await navigator.clipboard.writeText(minted.token);
      setCopied(true);
    } catch {
      // Clipboard can be unavailable (http origin); the token stays selectable.
    }
  }

  const active = pats?.filter(p => patStatus(p) === 'active').length ?? 0;
  return (
    <div className="card cred-section" style={{ marginBottom: 16 }}>
      <div className="cred-head">
        <div className="card-title" style={{ margin: 0 }}>
          Personal access tokens
          {pats && <span className="subtle cred-count">{active} active</span>}
        </div>
        {!creating && (
          <button type="button" onClick={openCreate} disabled={pats == null}>Create token</button>
        )}
      </div>
      <p className="subtle">
        Long-lived credentials for agents and scripts (MCP, CLI). A token acts with at most your
        permissions, narrowed by any restrictions you set when creating it, and can be revoked here
        at any time.
      </p>
      {err && <div className="login-err">{err}</div>}

      {minted && (
        <div className="login-notice cred-minted">
          <div>
            Token <strong>{minted.name}</strong> created. Copy it now: it is shown only once.
          </div>
          <code>{minted.token}</code>
          <div className="cred-form-actions" style={{ marginTop: 0 }}>
            <button type="button" onClick={copyMinted}>{copied ? 'Copied' : 'Copy'}</button>
            <button type="button" className="secondary" onClick={() => setMinted(null)}>Done</button>
          </div>
        </div>
      )}

      {pats == null ? (
        <div className="subtle">Loading...</div>
      ) : pats.length === 0 ? (
        !creating && (
          <div className="empty">
            No tokens yet. Create one for each agent or script, so each can be revoked on its own.
          </div>
        )
      ) : (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Scope</th>
              <th>Created</th>
              <th>Expires</th>
              <th>Last used</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {pats.map(p => {
              const status = patStatus(p);
              return (
                <tr key={p.id} className={status === 'active' ? undefined : 'cred-inactive'}>
                  <td><code>{p.name}</code></td>
                  <td className="subtle">
                    <div title={scopeDetail(p.scope)} style={{ cursor: 'help' }}>
                      {scopeSummary(p.scope)}
                    </div>
                    {p.parentId && (
                      <div style={{ fontSize: '0.85em' }}>
                        depth {p.depth ?? 0}, delegated from {p.parentId}
                      </div>
                    )}
                  </td>
                  <td className="subtle">{shortTs(p.createdAt)}</td>
                  <td className="subtle">{p.expiresAt ? shortTs(p.expiresAt) : 'never'}</td>
                  <td className="subtle">{p.lastUsedAt ? shortTs(p.lastUsedAt) : 'never'}</td>
                  <td>
                    <span className={`badge ${status === 'active' ? 'good' : 'bad'}`}>{status}</span>
                  </td>
                  <td className="cred-actions">
                    {status === 'active' ? (
                      <button className="danger" type="button" onClick={() => { void revoke(p); }}>Revoke</button>
                    ) : (
                      <button className="danger" type="button" onClick={() => { void remove(p); }}>Delete</button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {creating && (
        <form onSubmit={submit} className="cred-form">
          <div className="cred-form-title">New token</div>
          <div className="cred-grid">
            <label>
              Name
              <input value={form.name} onChange={e => set('name', e.target.value)}
                placeholder="claude-code" autoFocus />
            </label>
            <label>
              Expires (optional)
              <input type="datetime-local" value={form.expiry} onChange={e => set('expiry', e.target.value)} />
            </label>
          </div>
          <div className="cred-form-subtitle">
            Restrictions (optional). A blank field leaves that axis as your own permissions allow.
          </div>
          <div className="cred-grid">
            <label>
              Databases
              <input value={form.databases} onChange={e => set('databases', e.target.value)} placeholder="comma-separated" />
            </label>
            <label>
              Pools
              <input value={form.pools} onChange={e => set('pools', e.target.value)} placeholder="comma-separated" />
            </label>
            <label>
              Roles
              <input value={form.roles} onChange={e => set('roles', e.target.value)} placeholder="comma-separated" />
            </label>
            <label>
              Tools
              <input value={form.tools} onChange={e => set('tools', e.target.value)} placeholder="comma-separated" />
            </label>
            <label>
              Verb ceiling
              <select value={form.verbCeiling} onChange={e => set('verbCeiling', e.target.value)}>
                <option value="">(unrestricted)</option>
                {VERB_CEILINGS.map(v => <option key={v} value={v}>{v}</option>)}
              </select>
            </label>
            <label>
              Row cap
              <input type="number" min={1} value={form.maxRows} onChange={e => set('maxRows', e.target.value)} placeholder="unrestricted" />
            </label>
            <label>
              Statement timeout, ms
              <input type="number" min={1} value={form.stmtTimeoutMs} onChange={e => set('stmtTimeoutMs', e.target.value)} placeholder="unrestricted" />
            </label>
            <label className="checkbox-label cred-check">
              <input type="checkbox" checked={form.dropAdmin} onChange={e => set('dropAdmin', e.target.checked)} />
              {' '}Drop admin standing on this token
            </label>
          </div>
          <div className="cred-form-actions">
            <button type="submit" disabled={busy || !form.name.trim()}>
              {busy ? 'Creating...' : 'Create token'}
            </button>
            <button type="button" className="secondary" onClick={() => setCreating(false)} disabled={busy}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
