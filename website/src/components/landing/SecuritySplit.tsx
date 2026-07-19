import React from 'react';
import styles from './landing.module.css';

const QUERY = 'SELECT c_phone, c_mktsegment FROM customer';

function Panel({ user, rows }: { user: string; rows: [string, string][] }) {
  return (
    <div className={styles.splitPanel}>
      <div className={styles.splitHead}>
        {user} $ {QUERY}
      </div>
      <table className={styles.splitTable}>
        <thead>
          <tr>
            <th>c_phone</th>
            <th>c_mktsegment</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([phone, seg], i) => (
            <tr key={i}>
              <td className={phone === '***' ? styles.masked : undefined}>{phone}</td>
              <td>{seg}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function SecuritySplit(): React.ReactElement {
  return (
    <div className={styles.split}>
      <Panel
        user="alice (analyst)"
        rows={[
          ['***', 'BUILDING'],
          ['***', 'BUILDING'],
          ['***', 'BUILDING'],
        ]}
      />
      <Panel
        user="admin"
        rows={[
          ['25-989-741-2988', 'BUILDING'],
          ['23-768-687-3665', 'AUTOMOBILE'],
          ['13-761-547-5974', 'MACHINERY'],
        ]}
      />
    </div>
  );
}
