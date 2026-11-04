import type { TenantDbKind } from './types';

/** What only a superuser sets on a database: the keys that name its Postgres server. On a
  * `duckdb-file` database `dbName` names a catalog in the file, no server, and stays the writer's. */
export function superuserKeys(kind: TenantDbKind): Set<string> {
  const keys = new Set(['pgHost', 'pgPort', 'pgUser', 'pgPassword']);
  if (kind !== 'duckdb-file') keys.add('dbName');
  return keys;
}

/** The metastore an edit form shows: all of it to a superuser, and to anyone else what they may
  * change. */
export function editableMetastore(
  stored: Record<string, string>,
  kind: TenantDbKind,
  superuser: boolean,
): Record<string, string> {
  if (superuser) return { ...stored };
  const hidden = superuserKeys(kind);
  return Object.fromEntries(Object.entries(stored).filter(([k]) => !hidden.has(k)));
}

/** The metastore an edit sends, which replaces the stored one. A superuser's goes as typed. Anyone
  * else's carries the server's keys back as the row holds them, so that it reads as no change, and
  * drops what was typed for them. No response shows a password: left out, it is kept. */
export function metastoreToSend(
  stored: Record<string, string>,
  edited: Record<string, string>,
  kind: TenantDbKind,
  superuser: boolean,
): Record<string, string> {
  if (superuser) return { ...edited };
  const hidden = superuserKeys(kind);
  const kept = Object.entries(stored).filter(([k]) => hidden.has(k));
  const typed = Object.entries(edited).filter(([k]) => !hidden.has(k));
  return Object.fromEntries([...typed, ...kept]);
}
