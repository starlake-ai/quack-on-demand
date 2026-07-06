import { useMemo } from 'react';

/** DuckDB / standard SQL keywords surfaced in the Recent-statements
  * card. Kept deliberately small: the goal is "operator glances at it
  * and picks out structure", not lossless ANSI coverage. Case-insensitive. */
const SQL_KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'GROUP', 'BY', 'HAVING', 'ORDER', 'LIMIT', 'OFFSET',
  'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'FULL', 'CROSS', 'NATURAL', 'ON', 'USING',
  'UNION', 'INTERSECT', 'EXCEPT', 'ALL', 'ANY', 'SOME', 'DISTINCT', 'AS', 'WITH', 'RECURSIVE',
  'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'RETURNING',
  'CREATE', 'DROP', 'ALTER', 'RENAME', 'TABLE', 'VIEW', 'INDEX', 'SCHEMA',
  'DATABASE', 'CATALOG', 'IF', 'EXISTS', 'NOT', 'NULL', 'PRIMARY', 'KEY', 'FOREIGN',
  'REFERENCES', 'CONSTRAINT', 'DEFAULT', 'UNIQUE', 'CHECK',
  'ATTACH', 'DETACH', 'USE', 'INSTALL', 'LOAD', 'PRAGMA', 'COPY', 'EXPORT', 'IMPORT',
  'CALL', 'SECRET', 'TYPE', 'READ_ONLY', 'DATA_PATH',
  'BEGIN', 'COMMIT', 'ROLLBACK', 'TRANSACTION', 'TEMP', 'TEMPORARY',
  'CASCADE', 'RESTRICT', 'REPLACE', 'OR',
  'AND', 'IN', 'BETWEEN', 'LIKE', 'ILIKE', 'IS', 'TRUE', 'FALSE',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'CAST', 'OVER', 'PARTITION', 'WINDOW',
  'ASC', 'DESC', 'NULLS', 'FIRST', 'LAST', 'EXCLUDE', 'QUALIFY',
]);

type SqlToken = { text: string; cls: 'kw' | 'str' | 'num' | 'com' | 'id' | null };

function tokenizeSql(sql: string): SqlToken[] {
  // Order matters: comments and strings must beat the word/number patterns.
  const re = new RegExp(
    [
      '(--[^\\n]*)',                       // 1: line comment
      '(\\/\\*[\\s\\S]*?\\*\\/)',          // 2: block comment
      "('(?:[^'\\\\]|\\\\.|'')*')",        // 3: single-quoted string
      '("(?:[^"\\\\]|\\\\.|"")*")',        // 4: double-quoted identifier
      '(\\b\\d+(?:\\.\\d+)?\\b)',          // 5: number
      '([A-Za-z_][A-Za-z0-9_]*)',          // 6: word (keyword or identifier)
    ].join('|'),
    'g'
  );
  const out: SqlToken[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) {
    if (m.index > last) out.push({ text: sql.slice(last, m.index), cls: null });
    if      (m[1]) out.push({ text: m[1], cls: 'com' });
    else if (m[2]) out.push({ text: m[2], cls: 'com' });
    else if (m[3]) out.push({ text: m[3], cls: 'str' });
    else if (m[4]) out.push({ text: m[4], cls: 'id' });
    else if (m[5]) out.push({ text: m[5], cls: 'num' });
    else if (m[6]) {
      const w = m[6];
      out.push({ text: w, cls: SQL_KEYWORDS.has(w.toUpperCase()) ? 'kw' : null });
    }
    last = re.lastIndex;
  }
  if (last < sql.length) out.push({ text: sql.slice(last), cls: null });
  return out;
}

/** Render `sql` as a sequence of highlighted spans, preserving every
  * character (including whitespace). The tokenizer recognises: line
  * comments, block comments, single-quoted strings, double-quoted
  * identifiers, numbers, words (keyword vs identifier), and a catch-all
  * for everything else. Token classes map to CSS rules in styles.css
  * (`.sql-tok-*`). */
export function SqlHighlight({ sql }: { sql: string }) {
  // Memoize the token list so we don't re-tokenize on every parent re-render
  // of the polling page. Token-list shape is small even for a kilobyte of SQL.
  const tokens = useMemo(() => tokenizeSql(sql), [sql]);
  return (
    <>
      {tokens.map((t, i) =>
        t.cls === null
          ? t.text                      // whitespace and unclassified passthrough
          : <span key={i} className={`sql-tok-${t.cls}`}>{t.text}</span>
      )}
    </>
  );
}

export default SqlHighlight;
