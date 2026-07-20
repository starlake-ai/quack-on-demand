import React from 'react';
import styles from './landing.module.css';

export default function GrowthDiagram(): React.ReactElement {
  return (
    <svg className={styles.diagram} viewBox="0 0 720 200" role="img"
      aria-label="Growth path: one container, one node with your Postgres, Kubernetes cluster">
      <defs>
        <marker id="arrowhead" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
          <path className={styles.arrowHead} d="M0,0 L7,3 L0,6" fill="none" />
        </marker>
      </defs>
      {/* stage 1: one container */}
      <g>
        <rect className={styles.stageBox} x="10" y="45" width="180" height="110" rx="10" />
        <text className={styles.stageLabel} x="100" y="75" textAnchor="middle">One container</text>
        <text className={styles.stageSub} x="100" y="95" textAnchor="middle">uvx qod start</text>
        <text className={styles.stageSub} x="100" y="112" textAnchor="middle">embedded demo data</text>
        <rect className={styles.pod} x="80" y="122" width="40" height="18" rx="4" />
      </g>
      <line className={styles.arrow} x1="196" y1="100" x2="254" y2="100" />
      {/* stage 2: one node + your Postgres */}
      <g>
        <rect className={styles.stageBox} x="260" y="45" width="180" height="110" rx="10" />
        <text className={styles.stageLabel} x="350" y="75" textAnchor="middle">One node</text>
        <text className={styles.stageSub} x="350" y="95" textAnchor="middle">your Postgres catalog</text>
        <text className={styles.stageSub} x="350" y="112" textAnchor="middle">your S3 or disk</text>
        <rect className={styles.pod} x="315" y="122" width="30" height="18" rx="4" />
        <rect className={styles.pod} x="355" y="122" width="30" height="18" rx="4" />
      </g>
      <line className={styles.arrow} x1="446" y1="100" x2="504" y2="100" />
      {/* stage 3: kubernetes */}
      <g>
        <rect className={styles.stageBox} x="510" y="25" width="200" height="150" rx="10" />
        <text className={styles.stageLabel} x="610" y="55" textAnchor="middle">Kubernetes</text>
        <text className={styles.stageSub} x="610" y="75" textAnchor="middle">pools of pods, HA managers</text>
        <text className={styles.stageSub} x="610" y="92" textAnchor="middle">scale-to-zero tenants</text>
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <rect key={i} className={styles.pod}
            x={535 + (i % 3) * 52} y={104 + Math.floor(i / 3) * 28}
            width="40" height="18" rx="4" />
        ))}
      </g>
    </svg>
  );
}
