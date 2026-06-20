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
        // Default to "(all)" so the initial Users tab includes
        // superusers (tenant IS NULL) and every tenant's users. Picking
        // a specific tenant from the dropdown narrows from there.
        setSelected(ALL);
      })
      .catch(() => setSelected(ALL));
  }, []);

  // "(superusers)" is a UI-only sentinel; the backend has no
  // first-class concept for "list superusers only" via listUsers, so we
  // fetch all (tenant=null) and let UserSection narrow on the client.
  const superusersOnly = selected === '(superusers)';
  const usersFilter    = selected === ALL || superusersOnly ? null : selected;
  const tenantForRoles = (selected && selected !== ALL && !superusersOnly)
    ? selected
    : null;
  const tenantRow = tenants.find(t => t.id === tenantForRoles);

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
              <option key={t.id} value={t.id}>{t.displayName} ({t.id})</option>
            ))}
          </select>
        </label>
        {tenantRow && (
          <span className="subtle">
            Auth provider: <code>{tenantRow.authProvider}</code>
            {Object.keys(tenantRow.authConfig).length > 0 && (
              <> - <code>{JSON.stringify(tenantRow.authConfig)}</code></>
            )}
          </span>
        )}
      </div>

      <Tabs
        tabs={[
          { id: 'users',  label: 'Users',
            body: <UserSection
                    tenant={usersFilter}
                    tenants={tenants}
                    superusersOnly={superusersOnly}
                  /> },
          { id: 'groups', label: 'Groups',
            body: <GroupSection tenant={tenantForRoles} /> },
          { id: 'roles',  label: 'Roles',
            body: <RoleSection tenant={tenantForRoles} /> },
        ]}
      />
    </>
  );
}