import { useState, type ReactNode } from 'react';

export interface TabSpec {
  /** Stable id used for the active-tab key. */
  id:    string;
  /** Visible label on the tab button. */
  label: string;
  /** Rendered when the tab is active. */
  body:  ReactNode;
}

/** Tab strip, uncontrolled by default. The first tab in `tabs` is active
  * on mount; clicking a header swaps the visible body.
  *
  * Passing `activeId` switches to controlled mode: the caller owns the
  * active tab (updating it from `onSelect` and, when needed, switching
  * programmatically, e.g. a "Compare" action jumping to the Compare tab).
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
  activeId,
  onSelect,
}: {
  tabs:       TabSpec[];
  initialId?: string;
  /** Controlled active tab id. When set, the caller must update it from
    * `onSelect` for header clicks to take effect. */
  activeId?:  string;
  /** Fired on every header click (incl. re-clicks of the active tab),
    * after the tab becomes active. Not fired for the initial tab or a
    * programmatic `activeId` change. */
  onSelect?:  (id: string) => void;
}) {
  const [uncontrolled, setUncontrolled] = useState<string>(initialId ?? tabs[0]?.id ?? '');
  const active = activeId ?? uncontrolled;
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
              setUncontrolled(t.id);
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
