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
  * Only the active tab body is mounted. Each tab switch (or re-click
  * of the active tab) unmounts the previous body and mounts a fresh
  * instance, so callers get the same state they would on first display
  * of that tab -- fetched data, inline forms, and pickers all reset.
  * The cost is a refetch on every switch, which is acceptable for the
  * dev / admin UI scale and was explicitly requested.
  *
  * Nothing is persisted to the URL -- callers that need shareable
  * links should lift the active id into the route. */
export default function Tabs({
  tabs,
  initialId,
  onSelect,
}: {
  tabs:       TabSpec[];
  initialId?: string;
  /** Fired on every header click (incl. re-clicks of the active tab),
    * after the tab becomes active. Not fired for the initial tab. */
  onSelect?:  (id: string) => void;
}) {
  const [active, setActive] = useState<string>(initialId ?? tabs[0]?.id ?? '');
  // Bumped on every header click (incl. re-clicks of the active tab)
  // so the keyed panel below remounts. That gives callers the same
  // state they'd see on first display of the tab.
  const [nonce, setNonce]   = useState(0);
  const activeTab = tabs.find(t => t.id === active);
  return (
    <div className="tabs">
      <div className="tab-bar" role="tablist">
        {tabs.map(t => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={t.id === active}
            className={'tab' + (t.id === active ? ' active' : '')}
            onClick={() => {
              setActive(t.id);
              setNonce(n => n + 1);
              onSelect?.(t.id);
            }}
          >
            {t.label}
          </button>
        ))}
      </div>
      {/* `key={active-nonce}` unmounts the previous panel on every
          click and mounts a fresh instance, so the rendered body is
          the same first-display state every time. */}
      <div className="tab-body" role="tabpanel" key={`${active}-${nonce}`}>
        {activeTab?.body}
      </div>
    </div>
  );
}