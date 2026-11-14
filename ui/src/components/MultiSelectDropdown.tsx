import { useEffect, useRef, useState } from 'react';

export interface MultiSelectOption {
  value: string;
  label: string;
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
          border: '1px solid var(--color-muted)',
          borderRadius: 'var(--radius-control)',
          font: 'inherit',
          fontWeight: 400,
          boxSizing: 'border-box',
          lineHeight: 1.4,
          height: '2.6rem',
          textAlign: 'left',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          cursor: disabled ? 'not-allowed' : 'pointer',
          opacity: disabled ? 0.6 : 1,
          // The same drawn caret as a native <select> (styles.css), in the muted ink.
          backgroundImage:
            'linear-gradient(45deg, transparent 50%, var(--color-muted) 50%), linear-gradient(135deg, var(--color-muted) 50%, transparent 50%)',
          backgroundRepeat: 'no-repeat',
          backgroundPosition: 'right calc(.65rem + 5px) center, right .65rem center',
          backgroundSize: '5px 5px, 5px 5px',
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
