import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { TenantResponse } from '../api/types';
import GroupSection from '../components/GroupSection';
import RoleSection from '../components/RoleSection';
import Tabs from '../components/Tabs';
import UserSection from '../components/UserSection';

/** /users page. Top: tenant selector + per-tab UI. The selector also
  * supports two synthetic values:
  *   - "(all)"        : list every user across every tenant + the superusers
  *   - "(superusers)" : tenant IS NULL filter only
  *
  * Roles and Groups need a concrete tenant scope -- those tabs show a
  * hint when the selector is on a synthetic value. Identities aren't a
  * separate tab any more: the auth provider is a tenant attribute,
  * configured on the tenant create / detail page. */
type Selector = string | null;
const ALL = ''; // empty string sentinel for the "(all)" option

export default function Users() {
  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [selected, setSelected] = useState<Selector>(null);

  useEffect(() => {
    api.listTenants()
      .then(r => {
        setTenants(r.tenants);
        if (r.tenants.length > 0) setSelected(r.tenants[0].name);
        else setSelected(ALL);
      })
      .catch(() => setSelected(ALL));
  }, []);

  const usersFilter   = selected === ALL ? null : selected;
  const tenantForRoles = (selected && selected !== ALL && selected !== '(superusers)')
    ? selected
    : null;
  const tenantRow = tenants.find(t => t.name === tenantForRoles);

  return (
    <>
      <h1>Users &amp; access control</h1>
      <div className="row" style={{ gap: 12, alignItems: 'center', marginBottom: '1rem', flexWrap: 'wrap' }}>
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
        {tenantRow && (
          <span className="subtle">
            Auth provider: <code>{tenantRow.authProvider}</code>
            {Object.keys(tenantRow.authConfig).length > 0 && (
              <> — <code>{JSON.stringify(tenantRow.authConfig)}</code></>
            )}
          </span>
        )}
      </div>

      <Tabs
        tabs={[
          { id: 'users',  label: 'Users',
            body: <UserSection tenant={usersFilter === '(superusers)' ? null : usersFilter} /> },
          { id: 'groups', label: 'Groups',
            body: <GroupSection tenant={tenantForRoles} /> },
          { id: 'roles',  label: 'Roles',
            body: <RoleSection tenant={tenantForRoles} /> },
        ]}
      />
    </>
  );
}