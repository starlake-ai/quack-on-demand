import type { ProfilePool, ProfileUndropRequest } from './types';

/** The databases of the caller's own pools, each once, sorted: what the profile's "Recover a dropped
  * table" section offers to look into. */
export function databasesOf(pools: ProfilePool[]): string[] {
  return [...new Set(pools.map(p => p.tenantDb))].sort((a, b) => a.localeCompare(b));
}

/** The undrop request for one listed table: a blank new name is left out (the table keeps its name).
  * `tableId` names the listed drop: a name dropped twice is two entries, and the name alone
  * recovers the newest. */
export function undropRequest(
  tenantDb: string,
  schema: string,
  table: string,
  newName: string,
  tableId: number,
): ProfileUndropRequest {
  const asName = newName.trim();
  return asName
    ? { tenantDb, schema, table, asName, tableId }
    : { tenantDb, schema, table, tableId };
}
