import type { PoolCohort } from '../api/types';

/** Editable shape of one cohort in the form. Flat counts (mirroring the
  * single-distribution editor) plus a list of `key=value` node-label
  * pairs. Converted to the wire `PoolCohort` shape by `cohortDraftToWire`. */
export interface CohortDraft {
  wo: number;
  ro: number;
  dual: number;
  selectors: { key: string; value: string }[];
}

export function emptyCohort(): CohortDraft {
  return { wo: 0, ro: 0, dual: 1, selectors: [{ key: '', value: '' }] };
}

export function cohortDraftToWire(c: CohortDraft): PoolCohort {
  const nodeSelector: Record<string, string> = {};
  for (const s of c.selectors) {
    if (s.key.trim() !== '') nodeSelector[s.key.trim()] = s.value;
  }
  return {
    placement: { nodeSelector },
    distribution: { writeonly: c.wo, readonly: c.ro, dual: c.dual },
  };
}

export function cohortsTotal(cohorts: CohortDraft[]) {
  return cohorts.reduce(
    (acc, c) => ({ wo: acc.wo + c.wo, ro: acc.ro + c.ro, dual: acc.dual + c.dual }),
    { wo: 0, ro: 0, dual: 0 },
  );
}

interface Props {
  cohorts: CohortDraft[];
  onChange: (next: CohortDraft[]) => void;
}

export default function CohortEditor({ cohorts, onChange }: Props) {
  const totals = cohortsTotal(cohorts);
  const size = totals.wo + totals.ro + totals.dual;

  function updateCohort(i: number, patch: Partial<CohortDraft>) {
    onChange(cohorts.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));
  }
  function updateSelector(i: number, j: number, patch: Partial<{ key: string; value: string }>) {
    onChange(cohorts.map((c, idx) => {
      if (idx !== i) return c;
      const selectors = c.selectors.map((s, sIdx) => (sIdx === j ? { ...s, ...patch } : s));
      return { ...c, selectors };
    }));
  }

  return (
    <fieldset>
      <legend>
        Cohorts (size = {size}; inferred WriteOnly = {totals.wo}, ReadOnly = {totals.ro}, Dual = {totals.dual})
      </legend>
      {cohorts.map((c, i) => (
        <div key={i} style={{ border: '1px solid #ccc', padding: 8, marginBottom: 8 }}>
          <div
            className="row"
            style={{ gap: 12, justifyContent: 'space-between', alignItems: 'center' }}
          >
            <strong>Cohort {i + 1}</strong>
            {cohorts.length > 1 && (
              <button
                type="button"
                title="Remove this cohort"
                aria-label={`Remove cohort ${i + 1}`}
                onClick={() => onChange(cohorts.filter((_, idx) => idx !== i))}
                style={{ padding: '0 .5rem' }}
              >×</button>
            )}
          </div>
          <div className="row" style={{ gap: 8, marginTop: 4 }}>
            <label>WO <input type="number" min={0} value={c.wo}   onChange={e => updateCohort(i, { wo: +e.target.value })}   style={{ width: 56 }} /></label>
            <label>RO <input type="number" min={0} value={c.ro}   onChange={e => updateCohort(i, { ro: +e.target.value })}   style={{ width: 56 }} /></label>
            <label>Dual <input type="number" min={0} value={c.dual} onChange={e => updateCohort(i, { dual: +e.target.value })} style={{ width: 56 }} /></label>
          </div>
          <div style={{ marginTop: 6 }}>
            <em>nodeSelector (K8s node labels)</em>
            {c.selectors.map((s, j) => {
              const isLast = j === c.selectors.length - 1;
              return (
                <div
                  key={j}
                  className="row"
                  style={{ gap: 4, marginTop: 4, alignItems: 'center', flexWrap: 'nowrap' }}
                >
                  <input
                    placeholder="key (e.g. disktype)"
                    value={s.key}
                    onChange={e => updateSelector(i, j, { key: e.target.value })}
                    style={{ flex: 1, minWidth: 0 }}
                  />
                  <span>=</span>
                  <input
                    placeholder="value (e.g. ssd)"
                    value={s.value}
                    onChange={e => updateSelector(i, j, { value: e.target.value })}
                    style={{ flex: 1, minWidth: 0 }}
                  />
                  {c.selectors.length > 1 && (
                    <button
                      type="button"
                      title="Remove this label"
                      onClick={() => updateCohort(i, {
                        selectors: c.selectors.filter((_, sIdx) => sIdx !== j),
                      })}
                      style={{ padding: '0 .5rem' }}
                    >×</button>
                  )}
                  {isLast && (
                    <button
                      type="button"
                      title="Add another label"
                      onClick={() => updateCohort(i, {
                        selectors: [...c.selectors, { key: '', value: '' }],
                      })}
                      style={{ padding: '0 .5rem' }}
                    >+</button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ))}
      <button type="button" onClick={() => onChange([...cohorts, emptyCohort()])}>
        + add cohort
      </button>
    </fieldset>
  );
}

/** Standard "this won't work outside K8s" warning. Render alongside the
  * cohort toggle when `placementSupported` is false. */
export function PlacementUnsupportedWarning() {
  return (
    <p style={{
      color: '#8a4b00',
      background: '#fff4e0',
      border: '1px solid #f0c270',
      padding: '6px 10px',
      marginTop: 6,
    }}>
      This manager is not running on Kubernetes. Placement instructions will be
      saved with the pool (so they survive a YAML export to a K8s cluster) but
      ignored at runtime - every node will be spawned by the local backend
      regardless of nodeSelector.
    </p>
  );
}