import { describe, expect, it } from 'vitest';
import { ALL } from './scope';
import { NAV, visibleNav, type NavCtx, type ResolvedNavItem } from './navModel';

const acme = { kind: 'tenant', tenant: 'acme' } as const;
const superCtx: NavCtx = { superuser: true, admin: true, telemetry: true, fleet: true, starlake: true, scope: acme };

function flat(items: ResolvedNavItem[]): ResolvedNavItem[] {
  return items.flatMap(i => [i, ...flat(i.children)]);
}
const ids = (ctx: NavCtx) => flat(visibleNav(NAV, ctx)).map(i => i.id);
const byId = (ctx: NavCtx, id: string) => flat(visibleNav(NAV, ctx)).find(i => i.id === id);

describe('visibleNav', () => {
  it('shows the full tree to a superuser with every feature on, in order', () => {
    expect(ids(superCtx)).toEqual([
      'dashboard', 'servers',
      'workbench',
      'tenant', 'databases', 'catalog', 'pools', 'maintenance', 'branches', 'auth-provider', 'access-control',
      'access', 'users', 'groups', 'roles',
      'audit', 'control-plane', 'statements', 'usage',
      'settings', 'config', 'security-audit', 'profile',
    ]);
  });
  it('shows the security audit to a superuser at its path', () => {
    expect(byId(superCtx, 'security-audit')?.to).toBe('/settings/security-audit');
  });
  it('builds scoped and absolute targets', () => {
    expect(byId(superCtx, 'pools')?.to).toBe('/t/acme/pools');
    expect(byId(superCtx, 'statements')?.to).toBe('/t/acme/audit/statements');
    expect(byId(superCtx, 'servers')?.to).toBe('/servers');
    expect(byId(superCtx, 'tenant')?.to).toBeNull();
    expect(byId(superCtx, 'workbench')).toMatchObject({ to: null, external: 'workbench' });
  });
  it('hides superuser-only entries from a tenant admin', () => {
    const got = ids({ ...superCtx, superuser: false });
    expect(got).not.toContain('servers');
    expect(got).not.toContain('config');
    expect(got).not.toContain('security-audit');
    expect(got).toContain('profile');
  });
  it('hides fleet servers outside fleet mode, audit without telemetry, workbench without starlake', () => {
    expect(ids({ ...superCtx, fleet: false })).not.toContain('servers');
    const noTel = ids({ ...superCtx, telemetry: false });
    expect(noTel).not.toContain('audit');
    expect(noTel).not.toContain('statements');
    expect(ids({ ...superCtx, starlake: false })).not.toContain('workbench');
  });
  it('disables, not hides, tenant-only entries under All tenants', () => {
    const ctx = { ...superCtx, scope: ALL };
    expect(byId(ctx, 'pools')).toMatchObject({ disabled: true, to: '/all/pools' });
    expect(byId(ctx, 'groups')?.disabled).toBe(true);
    expect(byId(ctx, 'users')?.disabled).toBe(false);
    expect(byId(ctx, 'dashboard')?.disabled).toBe(false);
  });
  it('disables, not hides, the workbench for a superuser', () => {
    expect(byId(superCtx, 'workbench')).toMatchObject({
      disabled: true, disabledReason: 'Not available to superusers', external: 'workbench',
    });
    expect(byId({ ...superCtx, superuser: false }, 'workbench')).toMatchObject({ disabled: false, disabledReason: null });
    expect(byId({ ...superCtx, scope: ALL }, 'pools')?.disabledReason).toBe('Pick a tenant first');
  });
  it('reduces a profile-only session to workbench and profile', () => {
    const ctx: NavCtx = { superuser: false, admin: false, telemetry: true, fleet: true, starlake: true, scope: acme };
    expect(ids(ctx)).toEqual(['workbench', 'settings', 'profile']);
  });
});
