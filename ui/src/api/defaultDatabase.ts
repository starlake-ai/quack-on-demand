/** The catalog a database's nodes resolve unqualified table names in, as the server reads it
  * (`TenantDb.sessionCatalog`): `catalogAlias`, else `dbName`, else the database's name, or DuckDB's
  * `memory` catalog for kind=memory. */
export function ownCatalog(
  kind: string,
  metastore: Record<string, string> | undefined,
  name: string,
): string {
  const alias = metastore?.catalogAlias?.trim();
  if (alias) return alias;
  const dbName = metastore?.dbName?.trim();
  if (dbName) return dbName;
  return kind === 'memory' ? 'memory' : name;
}

/** What the form says of a default database the server would refuse (400
  * `default_database_mismatch`): it may only name the database's own catalog, compared ignoring
  * case. null when it is empty or names that catalog. */
export function defaultDatabaseError(
  value: string,
  kind: string,
  metastore: Record<string, string> | undefined,
  name: string,
): string | null {
  const v = value.trim();
  if (v === '') return null;
  const own = ownCatalog(kind, metastore, name);
  if (v.toLowerCase() === own.toLowerCase()) return null;
  return `Default database must be left empty or name this database's own catalog (${own}): ` +
    'unqualified table names resolve there. Name a table of another catalog by its catalog.';
}
