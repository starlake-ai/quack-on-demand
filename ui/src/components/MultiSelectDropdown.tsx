import { useEffect, useRef, useState } from 'react';

export interface MultiSelectOption {
  value: string;
  label: string;
  /** Hover text for the option, when its label alone does not say enough. */
  title?: string;
}

// Form dropdown holding a checkbox list: looks like a closed <select>, opens a
// panel where several values can be ticked. Closes on outside click and Escape.
// The caption is rendered here (not by a wrapping <label>) because a label
// around the trigger button would forward every click inside the panel to it.
export default function MultiSelectDropdown({
  caption,
  options,
  selected,
  onChange,
  disabled = false,
  placeholder = 'None selected',
}: {
  caption: string;
  options: MultiSelectOption[];
  selected: string[];
  onChange: (values: string[]) => void;
  disabled?: boolean;
  placeholder?: string;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

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

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  const toggle = (value: string) =>
    onChange(selected.includes(value) ? selected.filter(v => v !== value) : [...selected, value]);

  const summary = selected.length === 0 ? placeholder : selected.join(', ');

  return (
    <div ref={rootRef} style={{ position: 'relative', marginBottom: '.75rem', color: 'var(--text-soft)' }}>
      {caption}
      <button
        type="button"
        disabled={disabled}
        aria-label={`${caption}: ${summary}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'block',
          width: '100%',
          marginTop: '.25rem',
          padding: '.5rem 1.8rem .5rem .65rem',
          background: 'var(--bg-elev)',
          color: selected.length === 0 ? 'var(--text-mute)' : 'var(--text)',
          border: '1px solid var(--border)',
          borderRadius: 6,
          font: 'inherit',
          fontFamily: 'var(--mono)',
          boxSizing: 'border-box',
          lineHeight: 1.4,
          height: '2.4rem',
          textAlign: 'left',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          cursor: disabled ? 'not-allowed' : 'pointer',
          opacity: disabled ? 0.6 : 1,
          backgroundImage:
            "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 12 8'><path fill='%239aa0a6' d='M6 8L0 0h12z'/></svg>\")",
          backgroundRepeat: 'no-repeat',
          backgroundPosition: 'right .65rem center',
          backgroundSize: '10px 7px',
        }}
      >
        {summary}
      </button>
      {open && (
        <div
          className="dropdown-menu"
          role="listbox"
          aria-multiselectable="true"
          style={{
            position: 'absolute',
            top: 'calc(100% + 4px)',
            left: 0,
            right: 0,
            zIndex: 30,
            maxHeight: '16rem',
            overflowY: 'auto',
          }}
        >
          {options.length === 0 && <span className="subtle" style={{ padding: '.45rem .75rem' }}>Nothing to pick</span>}
          {options.map(o => (
            <label
              key={o.value}
              title={o.title}
              className="checkbox-label"
              style={{ margin: 0, padding: '.35rem .75rem', cursor: 'pointer', color: 'var(--text)' }}
            >
              <input
                type="checkbox"
                checked={selected.includes(o.value)}
                onChange={() => toggle(o.value)}
              />
              {' '}{o.label}
            </label>
          ))}
        </div>
      )}
    </div>
  );
}
