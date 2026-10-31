import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { NavIcon } from './NavIcons';
import type { ResolvedNavItem } from './navModel';
import { readCollapsed, writeCollapsed } from './sidebarPrefs';
import { goToStarlake } from './workbench';

function NavTree({ items, depth }: { items: ResolvedNavItem[]; depth: number }) {
  const { starlakeUrl } = useAuth();
  return (
    <ul className={`nav-tree depth-${depth}`}>
      {items.map(item => {
        const body = <><NavIcon name={item.icon} /><span className="nav-label">{item.label}</span></>;
        let entry: ReactNode;
        if (item.external === 'workbench') {
          entry = (
            <button
              type="button" className="nav-item" title="Opens in a new tab"
              aria-label="Workbench (opens in a new tab)"
              onClick={() => { if (starlakeUrl) void goToStarlake(starlakeUrl); }}
            >
              {body}<span className="external-arrow nav-label" aria-hidden="true">↗</span>
            </button>
          );
        } else if (item.to == null) {
          entry = <div className="nav-group" title={item.label}>{body}</div>;
        } else if (item.disabled) {
          entry = <span className="nav-item disabled" aria-disabled="true" title="Pick a tenant first">{body}</span>;
        } else {
          entry = (
            <NavLink to={item.to} title={item.label} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
              {body}
            </NavLink>
          );
        }
        return (
          <li key={item.id}>
            {entry}
            {item.children.length > 0 && <NavTree items={item.children} depth={depth + 1} />}
          </li>
        );
      })}
    </ul>
  );
}

/** App chrome: left sidebar (brand, tenant switcher, nav tree, user footer) and the scrolling
  * content column. Collapses to an icon rail on desktop; below 900px it is an overlay drawer. */
export default function SidebarLayout({ items, switcher, children }: {
  items: ResolvedNavItem[];
  switcher: (collapsed: boolean, expand: () => void) => ReactNode;
  children: ReactNode;
}) {
  const { username, role, logout } = useAuth();
  const [collapsed, setCollapsed] = useState(() => readCollapsed());
  const [drawerOpen, setDrawerOpen] = useState(false);
  const location = useLocation();

  function setCollapsedPersist(v: boolean) {
    setCollapsed(v);
    writeCollapsed(v);
  }

  useEffect(() => { setDrawerOpen(false); }, [location.pathname]);
  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDrawerOpen(false); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  return (
    <div className={`app-shell${collapsed ? ' collapsed' : ''}${drawerOpen ? ' drawer-open' : ''}`}>
      <header className="mobile-bar">
        <button type="button" aria-label="Open navigation" onClick={() => setDrawerOpen(true)}>☰</button>
        <img src="/ui/mark-dark.svg" alt="" className="brand-mark" />
        <span className="brand-name">Quack on Demand</span>
      </header>
      {drawerOpen && <div className="drawer-scrim" onClick={() => setDrawerOpen(false)} />}
      <aside className="sidebar" aria-label="Main navigation">
        <div className="sidebar-brand">
          <img src="/ui/mark-dark.svg" alt="" className="brand-mark" />
          <span className="nav-label brand-name">Quack on Demand</span>
        </div>
        {switcher(collapsed, () => setCollapsedPersist(false))}
        <nav className="sidebar-nav">
          <NavTree items={items} depth={0} />
        </nav>
        <div className="sidebar-footer">
          <span className="user-pill nav-label" title={username ?? ''}>
            <span className="user-name">{username}</span> <span className="role">{role}</span>
          </span>
          <div className="sidebar-actions">
            <button
              type="button" className="collapse-toggle"
              aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              onClick={() => setCollapsedPersist(!collapsed)}
            >
              {collapsed ? '»' : '«'}
            </button>
            <button type="button" className="secondary" onClick={() => { void logout(); }}>
              <span className="nav-label">Sign out</span>
              <span className="signout-short" aria-hidden="true">⏻</span>
            </button>
          </div>
        </div>
      </aside>
      <main>{children}</main>
    </div>
  );
}
