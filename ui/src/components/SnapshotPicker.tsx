import type { CatalogSnapshotEntry, CatalogTagEntry } from '../api/types';

/** Selector value encoding shared by every consumer of this picker:
 *  - ''            -> current (no selector)
 *  - 'id:<n>'      -> a snapshot id (asOf)
 *  - 'tag:<name>'  -> a named tag (asOfTag)
 *  - 'ts:<iso>'    -> a timestamp (asOfTs), from the datetime-local input
 *
 * Consumers parse this string into whichever of asOf / asOfTag / asOfTs the
 * target endpoint expects; the picker itself stays a dumb, controlled input.
 */
export type SnapshotSelectorValue = string;

export function parseSnapshotSelector(
  value: SnapshotSelectorValue
): { asOf?: number; asOfTag?: string; asOfTs?: string } {
  if (!value) return {};
  if (value.startsWith('id:')) return { asOf: Number(value.slice(3)) };
  if (value.startsWith('tag:')) return { asOfTag: value.slice(4) };
  if (value.startsWith('ts:')) return { asOfTs: value.slice(3) };
  return {};
}

interface Props {
  tenant: string;
  tenantDb: string;
  value: SnapshotSelectorValue;
  onChange: (v: SnapshotSelectorValue) => void;
  snapshots: CatalogSnapshotEntry[];
  tags: CatalogTagEntry[];
  /** Optional label override; defaults to "Snapshot". */
  label?: string;
}

/** Reusable snapshot selector: current option, named tags first (with a
 * "hold" marker for protected tags), snapshot ids newest-first, plus a
 * datetime-local input for an arbitrary point in time. Controlled + dumb:
 * all data comes in via props, and it only ever emits a selector string
 * through onChange -- callers own state and any asOf/asOfTag/asOfTs
 * translation. */
export default function SnapshotPicker({ tenant, tenantDb, value, onChange, snapshots, tags, label }: Props) {
  const isTs = value.startsWith('ts:');
  const selectValue = isTs ? '' : value;
  const tsValue = isTs ? value.slice(3).slice(0, 16) : '';

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <label>
        {label ?? 'Snapshot'}&nbsp;
        <select
          value={selectValue}
          onChange={e => onChange(e.target.value)}
          aria-label={`${label ?? 'Snapshot'} for ${tenant}/${tenantDb}`}
        >
          <option value="">current</option>
          {tags.filter(t => t.exists).map(t => (
            <option key={`tag-${t.name}`} value={`tag:${t.name}`}>
              {t.name} ({t.snapshotId}{t.protected ? ', hold' : ''})
            </option>
          ))}
          {[...snapshots]
            .sort((a, b) => b.snapshotId - a.snapshotId)
            .map(s => (
              <option key={s.snapshotId} value={`id:${s.snapshotId}`}>
                {s.snapshotId} ({new Date(s.committedAt).toLocaleString()})
              </option>
            ))}
        </select>
      </label>
      <label>
        at time&nbsp;
        <input
          type="datetime-local"
          value={tsValue}
          onChange={e => {
            const v = e.target.value;
            onChange(v ? `ts:${new Date(v).toISOString()}` : '');
          }}
        />
      </label>
    </span>
  );
}
