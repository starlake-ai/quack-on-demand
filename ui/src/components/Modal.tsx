import { CSSProperties, ReactNode } from 'react';

/** Shared modal chrome: fixed backdrop (click closes) + centered card (clicks stop
 * propagation). `height` switches to the tall flex-column variant used by panels with
 * scrollable tabbed bodies; `scrollBackdrop` lets a long form scroll the backdrop. */
export function Modal({ maxWidth = 560, height, scrollBackdrop = false, onClose, children }: {
  maxWidth?: number;
  height?: string;
  scrollBackdrop?: boolean;
  onClose: () => void;
  children: ReactNode;
}) {
  const card: CSSProperties = height
    ? {
        width: '90%', maxWidth, height, maxHeight: 'calc(100vh - 4rem)',
        display: 'flex', flexDirection: 'column',
      }
    : { width: '90%', maxWidth };
  return (
    <div
      className="modal-backdrop"
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
        display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
        zIndex: 100, paddingTop: '4rem',
        ...(scrollBackdrop ? { overflowY: 'auto' as const, paddingBottom: '2rem' } : {}),
      }}
    >
      <div className="modal card" onClick={ev => ev.stopPropagation()} style={card}>
        {children}
      </div>
    </div>
  );
}
