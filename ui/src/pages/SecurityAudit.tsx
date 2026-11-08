import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, errorMessage } from '../api/client';
import type { SecurityAuditResponse } from '../api/types';
import { AREA_LABEL, STATUS_BADGE, STATUS_LABEL, groupByArea, summaryLine, visibleFindings } from '../api/securityAudit';

export default function SecurityAudit() {
  const [report, setReport] = useState<SecurityAuditResponse | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [showPassing, setShowPassing] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setErr(null);
    api.securityAudit()
      .then(r => { setReport(r); setLoading(false); })
      .catch(e => {
        setErr(e instanceof ApiError && e.status === 403 ? 'Superuser only.' : errorMessage(e));
        setLoading(false);
      });
  }, []);

  useEffect(load, [load]);

  return (
    <div className="card">
      <h2>Security audit</h2>
      {report && (
        <p>
          {summaryLine(report.summary)}{' '}
          <span style={{ color: 'var(--text-mute)' }}>
            ({report.replica}, {new Date(report.generatedAt).toLocaleString()})
          </span>{' '}
          <button onClick={load} disabled={loading}>Re-run</button>
        </p>
      )}
      {loading && <p>Loading…</p>}
      {err && <p style={{ color: 'var(--bad)' }}>{err}</p>}
      {report && !loading && (
        <>
          <label>
            <input type="checkbox" checked={showPassing} onChange={e => setShowPassing(e.target.checked)} />{' '}
            Show passing checks
          </label>
          {groupByArea(visibleFindings(report.findings, showPassing)).map(g => (
            <section key={g.area}>
              <h3>{AREA_LABEL[g.area]}</h3>
              <table className="config-table">
                <tbody>
                  {g.findings.map(f => (
                    <tr key={f.id}>
                      <td><span className={STATUS_BADGE[f.status]}>{STATUS_LABEL[f.status]}</span></td>
                      <td>
                        <strong>{f.title}</strong>
                        <div>{f.detail}</div>
                        {f.fix && <div style={{ color: 'var(--text-mute)' }}>Fix: {f.fix}</div>}
                      </td>
                      <td><a href={f.docs} target="_blank" rel="noreferrer">Docs</a></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ))}
        </>
      )}
    </div>
  );
}
