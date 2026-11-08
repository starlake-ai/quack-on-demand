import { scopePrefix, type Scope } from './scope';

export type IconName =
  | 'dashboard' | 'server' | 'tenant' | 'database' | 'catalog' | 'pool' | 'maintenance'
  | 'branch' | 'key' | 'shield' | 'access' | 'user' | 'group' | 'role' | 'audit' | 'log'
  | 'statements' | 'usage' | 'workbench' | 'settings' | 'config' | 'profile';

/** Visibility rules; an entry shows only when every rule holds. */
export type Rule = 'superuser' | 'admin' | 'telemetry' | 'fleet' | 'starlake';

export interface NavItem {
  id: string;
  label: string;
  icon: IconName;
  /** Path under the scope prefix (`pools`), for scoped entries. */
  section?: string;
  /** Absolute path, for entries outside the scope (`/settings/config`). */
  path?: string;
  /** Opens the Starlake workbench in a new tab instead of routing. */
  external?: 'workbench';
  /** Disabled (not hidden) under All tenants. */
  needsTenant?: boolean;
  rules?: Rule[];
  children?: NavItem[];
}

/** The whole sidebar. Visibility lives here, not in JSX; the server still enforces every gate. */
export const NAV: NavItem[] = [
  {
    id: 'dashboard', label: 'Dashboard', icon: 'dashboard', section: 'dashboard', rules: ['admin'],
    children: [
      { id: 'servers', label: 'Fleet servers', icon: 'server', path: '/servers', rules: ['admin', 'superuser', 'fleet'] },
    ],
  },
  { id: 'workbench', label: 'Workbench', icon: 'workbench', external: 'workbench', rules: ['starlake'] },
  {
    id: 'tenant', label: 'Tenant', icon: 'tenant', rules: ['admin'],
    children: [
      {
        id: 'databases', label: 'Databases', icon: 'database', section: 'databases', needsTenant: true,
        children: [
          { id: 'catalog', label: 'Catalog', icon: 'catalog', section: 'catalog', needsTenant: true },
        ],
      },
      { id: 'pools', label: 'Pools', icon: 'pool', section: 'pools', needsTenant: true },
      { id: 'maintenance', label: 'Maintenance', icon: 'maintenance', section: 'maintenance', needsTenant: true },
      { id: 'branches', label: 'Branches', icon: 'branch', section: 'branches', needsTenant: true },
      { id: 'auth-provider', label: 'Auth Provider', icon: 'key', section: 'auth-provider', needsTenant: true },
      { id: 'access-control', label: 'Access Control', icon: 'shield', section: 'access-control', needsTenant: true },
    ],
  },
  {
    id: 'access', label: 'Users & Access Controls', icon: 'access', rules: ['admin'],
    children: [
      { id: 'users', label: 'Users', icon: 'user', section: 'users' },
      { id: 'groups', label: 'Groups', icon: 'group', section: 'groups', needsTenant: true },
      { id: 'roles', label: 'Roles', icon: 'role', section: 'roles', needsTenant: true },
    ],
  },
  {
    id: 'audit', label: 'Audit', icon: 'audit', rules: ['admin', 'telemetry'],
    children: [
      { id: 'control-plane', label: 'Control Plane', icon: 'log', section: 'audit/control-plane' },
      { id: 'statements', label: 'Statements', icon: 'statements', section: 'audit/statements' },
      { id: 'usage', label: 'Usage', icon: 'usage', section: 'audit/usage' },
    ],
  },
  {
    id: 'settings', label: 'Settings', icon: 'settings',
    children: [
      { id: 'config', label: 'Config', icon: 'config', path: '/settings/config', rules: ['admin', 'superuser'] },
      { id: 'security-audit', label: 'Security audit', icon: 'shield', path: '/settings/security-audit', rules: ['admin', 'superuser'] },
      { id: 'profile', label: 'Profile', icon: 'profile', path: '/settings/profile' },
    ],
  },
];

export interface NavCtx {
  superuser: boolean;
  admin: boolean;
  telemetry: boolean;
  fleet: boolean;
  starlake: boolean;
  scope: Scope;
}

export interface ResolvedNavItem {
  id: string;
  label: string;
  icon: IconName;
  /** Router target; null for group headers and external entries. */
  to: string | null;
  external: 'workbench' | null;
  disabled: boolean;
  children: ResolvedNavItem[];
}

function holds(rule: Rule, ctx: NavCtx): boolean {
  switch (rule) {
    case 'superuser': return ctx.superuser;
    case 'admin': return ctx.admin;
    case 'telemetry': return ctx.telemetry;
    case 'fleet': return ctx.fleet;
    case 'starlake': return ctx.starlake;
  }
}

export function visibleNav(items: NavItem[], ctx: NavCtx): ResolvedNavItem[] {
  return items.flatMap(item => {
    if (!(item.rules ?? []).every(r => holds(r, ctx))) return [];
    const children = visibleNav(item.children ?? [], ctx);
    const to = item.section != null ? `${scopePrefix(ctx.scope)}/${item.section}` : item.path ?? null;
    // A group header whose every child is hidden has nothing to show.
    if (to == null && item.external == null && children.length === 0) return [];
    return [{
      id: item.id,
      label: item.label,
      icon: item.icon,
      to,
      external: item.external ?? null,
      disabled: item.needsTenant === true && ctx.scope.kind === 'all',
      children,
    }];
  });
}
