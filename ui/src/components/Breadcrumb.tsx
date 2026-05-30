import { Link } from 'react-router-dom';

export interface Crumb {
  /** Display text. */
  label: string;
  /** Target path. Omit on the last item (current page) — it renders as bold text. */
  to?: string;
}

interface Props {
  items: Crumb[];
}

/** Universal breadcrumb. Items with `to` render as <Link>; the last
  * (current) item should omit `to` and renders as bold non-clickable
  * text. Inline-styled so it works on both className-based pages
  * (TenantDetail) and inline-styled pages (PoolDetail, CatalogTableDetail). */
export default function Breadcrumb({ items }: Props) {
  return (
    <nav
      aria-label="Breadcrumb"
      style={{
        marginBottom: 16,
        color: '#555',
        fontSize: '0.95rem',
      }}
    >
      {items.map((c, i) => {
        const isLast = i === items.length - 1;
        const sep = i > 0 ? <span style={{ margin: '0 8px', color: '#bbb' }}>/</span> : null;
        return (
          <span key={i}>
            {sep}
            {c.to && !isLast
              ? <Link to={c.to} style={{ color: '#1166cc', textDecoration: 'none' }}>{c.label}</Link>
              : <strong style={{ color: '#333' }}>{c.label}</strong>}
          </span>
        );
      })}
    </nav>
  );
}