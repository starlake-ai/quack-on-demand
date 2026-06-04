import { useState, type ReactNode } from 'react';

export interface TabSpec {
  /** Stable id used for the active-tab key. */
  id:    string;
  /** Visible label on the tab button. */
  label: string;
  /** Rendered when the tab is active. */
  body:  ReactNode;
}

/** Plain useState-driven tab strip. The first tab in `tabs` is active
  * on mount; clicking a header swaps the visible body.
  *
  * Every tab body is mounted once and stays mounted -- inactive ones
  * are hidden with `display:none`. This preserves each tab's local
  * state (fetched data, open inline forms, picked rows in two-/three-
  * pane layouts) across tab switches. The cost is that all tabs fetch
  * on first page load instead of lazily, which is acceptable for the
  * dev / admin UI scale.
  *
  * Nothing is persisted to the URL -- callers that need shareable
  * links should lift the active id into the route. */
export default function Tabs({
  tabs,
  initialId,
}: {
  tabs:       TabSpec[];
  initialId?: string;
}) {
  const [active, setActive] = useState<string>(initialId ?? tabs[0]?.id ?? '');
  return (
    <div className="tabs">
      <div className="tab-bar" role="tablist">
        {tabs.map(t => (
          <button
            key={t.id}
            role="tab"
            aria-selected={t.id === active}
            className={'tab' + (t.id === active ? ' active' : '')}
            onClick={() => setActive(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="tab-body" role="tabpanel">
        {tabs.map(t => (
          <div key={t.id} hidden={t.id !== active}>
            {t.body}
          </div>
        ))}
      </div>
    </div>
  );
}