import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { PoolResponse, TenantResponse } from '../api/types';

/** Superuser filter data shared by the History / Usage / Audit pages: the tenant list
 * (fetched only for superusers - tenant admins are already pinned server-side) and,
 * when `withPools`, the pool list with a per-tenant pool-name derivation. */
export function useTenantPoolOptions(superuser: boolean, withPools = true) {
  const [tenantOptions, setTenantOptions] = useState<TenantResponse[]>([]);
  const [poolOptions, setPoolOptions] = useState<PoolResponse[]>([]);

  useEffect(() => {
    if (!withPools) return;
    api.listPools().then(r => setPoolOptions(r.pools)).catch(() => setPoolOptions([]));
  }, [withPools]);

  useEffect(() => {
    if (superuser) {
      api.listTenants().then(r => setTenantOptions(r.tenants)).catch(() => setTenantOptions([]));
    }
  }, [superuser]);

  /** Distinct pool names, narrowed to `tenant` when non-empty. */
  const poolNamesFor = (tenant: string): string[] =>
    [...new Set(poolOptions.filter(p => !tenant || p.tenant === tenant).map(p => p.pool))].sort();

  return { tenantOptions, poolNamesFor };
}

/** Filter-dropdown label for a tenant: just the id when displayName is only the id. */
export function tenantOptionLabel(t: TenantResponse): string {
  return t.displayName === t.id ? t.id : `${t.displayName} (${t.id})`;
}
