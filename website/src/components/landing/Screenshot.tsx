import React, { useCallback, useEffect, useState } from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './landing.module.css';

export default function Screenshot({
  src,
  alt,
  caption,
  fallbackSrc,
}: {
  src: string;
  alt: string;
  caption: string;
  fallbackSrc?: string;
}): React.ReactElement {
  const primary = useBaseUrl(src);
  const fallback = useBaseUrl(fallbackSrc ?? '');
  const [state, setState] = useState<'primary' | 'fallback' | 'missing'>('primary');
  const [zoomed, setZoomed] = useState(false);
  const onError = () =>
    setState((s) => (s === 'primary' && fallbackSrc ? 'fallback' : 'missing'));
  const close = useCallback(() => setZoomed(false), []);

  useEffect(() => {
    if (!zoomed) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
    };
  }, [zoomed, close]);

  const current = state === 'primary' ? primary : fallback;

  return (
    <figure className={styles.figure}>
      {state === 'missing' ? (
        <div className={styles.placeholder}>screenshot coming soon</div>
      ) : (
        <button
          type="button"
          className={styles.zoomTrigger}
          onClick={() => setZoomed(true)}
          aria-label={`View full size: ${alt}`}
        >
          <img src={current} alt={alt} loading="lazy" onError={onError} />
        </button>
      )}
      <figcaption className={styles.caption}>{caption}</figcaption>
      {zoomed && state !== 'missing' && (
        <div
          className={styles.lightbox}
          role="dialog"
          aria-modal="true"
          aria-label={alt}
          onClick={close}
        >
          <img src={current} alt={alt} />
        </div>
      )}
    </figure>
  );
}
