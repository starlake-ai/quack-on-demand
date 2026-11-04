/** What the create form says of the part of a new database's name after the tenant's prefix, or
  * null when the form takes it. Like `Names.databaseNameRefusal` it refuses `__` and an underscore
  * at its end, because node ids and Secret names put `--` between names. It also asks for a letter
  * first, as it always has, which the server does not of a name already prefixed. */
export function databaseSuffixError(suffix: string): string | null {
  if (suffix.length === 0) return "Suffix is required (e.g. 'prod', 'eu', 'tpch1').";
  if (!/^[A-Za-z][A-Za-z0-9]*(_[A-Za-z0-9]+)*$/.test(suffix)) {
    return 'Suffix must be a letter, then letters, digits and single underscores, not ending with an underscore.';
  }
  return null;
}
