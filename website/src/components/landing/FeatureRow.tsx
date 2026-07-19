import React from 'react';
import clsx from 'clsx';
import styles from './landing.module.css';

export default function FeatureRow({
  title,
  flip = false,
  visual,
  children,
}: {
  title: string;
  flip?: boolean;
  visual: React.ReactNode;
  children: React.ReactNode;
}): React.ReactElement {
  return (
    <div className={clsx(styles.row, flip && styles.rowFlip)}>
      <div className={styles.visual}>{visual}</div>
      <div className={styles.copy}>
        <h3>{title}</h3>
        {children}
      </div>
    </div>
  );
}
