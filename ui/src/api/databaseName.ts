/** What the create form says of the part of a new database's name after the tenant's prefix, or
  * null when the server takes it (`Names.databaseNameRefusal`): `__`, or an underscore at its end,
  * is refused because node ids and Secret names put `--` between names. */
export function databaseSuffixError(suffix: string): string | null {
  if (suffix.length === 0) return "Suffix is required (e.g. 'prod', 'eu', 'tpch1').";
  if (!/^[A-Za-z][A-Za-z0-9]*(_[A-Za-z0-9]+)*$/.test(suffix)) {
    return 'Suffix must be a letter, then letters, digits and single underscores, not ending with an underscore.';
  }
  return null;
}
