/** The init SQL an edit form sends: the text as typed, only when the field was touched and the text
  * differs from the stored one, else undefined (nothing sent). Never trimmed: the server keeps a
  * text's trust only for an unchanged resend, so a re-trimmed copy of a stored text would count as a
  * change (a superuser's save would vouch for a tenant admin's text it never looked at; a tenant
  * admin's unrelated save would demote a superuser's). */
export function initSqlToSend(stored: string | undefined, draft: string, touched: boolean): string | undefined {
  return touched && draft !== (stored ?? '') ? draft : undefined;
}
