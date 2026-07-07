import { useEffect, useRef, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';

interface NavDropdownItem {
  to: string;
  label: string;
}

// Nav-bar dropdown: a trigger styled like the sibling nav links plus a panel of
// router links. Closes on item click, outside click, and Escape.
export default function NavDropdown({
  label,
  items,
}: {
  label: string;
  items: NavDropdownItem[];
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const location = useLocation();

  const active = items.some(
    i => location.pathname === i.to || location.pathname.startsWith(`${i.to}/`),
  );

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="nav-dropdown" ref={rootRef}>
      <button type="button" className={active ? 'active' : ''} onClick={() => setOpen(o => !o)}>
        {label} {'▾'}
      </button>
      {open && (
        <div className="dropdown-menu">
          {items.map(i => (
            <NavLink
              key={i.to}
              to={i.to}
              className={({ isActive }) => `dropdown-item${isActive ? ' active' : ''}`}
              onClick={() => setOpen(false)}
            >
              {i.label}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}
