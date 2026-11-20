/** Typed-name confirmation for deletes that cannot be undone (a tenant, a database): the Delete
  * button stays off until the name is typed. The same rule as the CLI's prompt
  * (`qod_cli/commands/_confirm.py`): exact and case-sensitive, surrounding spaces aside. */
export function typedNameMatches(typed: string, expected: string): boolean {
  return expected.length > 0 && typed.trim() === expected;
}
