import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { PoolResponse } from '../api/types';
import Breadcrumb from '../components/Breadcrumb';

export default function ScalePool() {
  const { tenant, pool } = useParams<{ tenant: string; pool: string }>();
  const nav = useNavigate();
  const [current, setCurrent] = useState<PoolResponse | null>(null);
  const [ro, setRo]   = useState(0);
  const [wo, setWo]   = useState(0);
  const [dual, setDual] = useState(0);
  const [force, setForce] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!tenant || !pool) return;
    api.poolStatus(tenant, pool).then(p => {
      setCurrent(p);
      setRo(p.nodes.filter(n => n.role === 'READONLY' || n.role === 'ReadOnly').length);
      setWo(p.nodes.filter(n => n.role === 'WRITEONLY' || n.role === 'WriteOnly').length);
      setDual(p.nodes.filter(n => n.role === 'DUAL' || n.role === 'Dual').length);
    }).catch(e => setErr(String(e)));
  }, [tenant, pool]);

  const target = ro + wo + dual;
  const isShrink = current ? target < current.nodes.length : false;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!tenant || !pool) return;
    try {
      await api.scalePool({
        tenant, pool, targetSize: target,
        roleDistribution: { writeonly: wo, readonly: ro, dual },
        force
      });
      nav(`/pool/${tenant}/${pool}`);
    } catch (e) { setErr(String(e)); }
  }

  if (!current) return <p>Loading…</p>;

  return (
    <form onSubmit={submit}>
      <Breadcrumb
        items={[
          { label: 'Tenants', to: '/tenants' },
          { label: tenant!,   to: `/tenant/${encodeURIComponent(tenant!)}` },
          { label: pool!,     to: `/pool/${encodeURIComponent(tenant!)}/${encodeURIComponent(pool!)}` },
          { label: 'Scale' },
        ]}
      />
      <h2>Scale {tenant}/{pool}</h2>
      {err && <p style={{ color: 'red' }}>{err}</p>}
      <p>Current size: {current.nodes.length}. Target size: {target}.</p>
      <fieldset>
        <legend>Role distribution</legend>
        <label>WriteOnly <input type="number" min={0} value={wo} onChange={e => setWo(+e.target.value)} /></label>
        <label>ReadOnly  <input type="number" min={0} value={ro} onChange={e => setRo(+e.target.value)} /></label>
        <label>Dual      <input type="number" min={0} value={dual} onChange={e => setDual(+e.target.value)} /></label>
      </fieldset>
      {isShrink && (
        <label style={{ display: 'block', marginTop: '1rem', color: 'crimson' }}>
          <input type="checkbox" checked={force} onChange={e => setForce(e.target.checked)} />
          {' '}Force (skip graceful drain - outstanding queries fail)
        </label>
      )}
      <button type="submit" disabled={target === 0}>Apply</button>
    </form>
  );
}