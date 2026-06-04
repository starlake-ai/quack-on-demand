import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { TenantResponse } from '../api/types';
import GroupSection from '../components/GroupSection';
import IdentitySection from '../components/IdentitySection';
import RoleSection from '../components/RoleSection';
import Tabs from '../components/Tabs';
import UserSection from '../components/UserSection';

/** /users page. Top: tenant selector + per-tab UI. The selector also
  * supports two synthetic values:
  *   - "(all)"        : list every user across every tenant + the superusers
  *   - "(superusers)" : tenant IS NULL filter only
  *
  * Roles, Groups, and Identities all need a concrete tenant scope --
  * those tabs show a hint when the selector is on a synthetic value. */
type Selector = string | null;
const ALL = ''; // empty string sentinel for the "(all)" option

export default function Users() {
  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [selected, setSelected] = useState<Selector>(null);

  useEffect(() => {
    api.listTenants()
      .then(r => {
        setTenants(r.tenants);
        // Default to the first concrete tenant if there is one; otherwise
        // (all). The selector is a free-text-but-discrete <select> so
        // the value is one of: "" (all), "(superusers)", or a tenant name.
        if (r.tenants.length > 0) setSelected(r.tenants[0].name);
        else setSelected(ALL);
      })
      .catch(() => setSelected(ALL));
  }, []);

  // The Users tab accepts a tenant string (or undefined = every user);
  // the other tabs need a concrete tenant.
  const usersFilter   = selected === ALL ? null : selected;
  const tenantForRoles = (selected && selected !== ALL && selected !== '(superusers)')
    ? selected
    : null;

  return (
    <>
      <h1>Users &amp; access control</h1>
      <div className="row" style={{ gap: 12, alignItems: 'center', marginBottom: '1rem' }}>
        <label>
          Tenant:&nbsp;
          <select
            value={selected ?? ''}
            onChange={ev => setSelected(ev.target.value === ALL ? ALL : ev.target.value)}
          >
            <option value={ALL}>(all)</option>
            <option value="(superusers)">(superusers)</option>
            {tenants.map(t => (
              <option key={t.name} value={t.name}>{t.name}</option>
            ))}
          </select>
        </label>
        <span className="subtle">
          Roles, Groups, and Identities are per-tenant; the Users tab accepts <code>(all)</code>.
        </span>
      </div>

      <Tabs
        tabs={[
          { id: 'users',      label: 'Users',
            body: <UserSection tenant={usersFilter === '(superusers)' ? null : usersFilter} /> },
          { id: 'groups',     label: 'Groups',
            body: <GroupSection tenant={tenantForRoles} /> },
          { id: 'roles',      label: 'Roles',
            body: <RoleSection tenant={tenantForRoles} /> },
          { id: 'identities', label: 'Identities',
            body: tenantForRoles
              ? <IdentitySection tenantId={tenantForRoles} />
              : <div className="card subtle">Pick a concrete tenant above to manage its identity allowlist.</div> },
        ]}
      />
    </>
  );
}
