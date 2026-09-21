package ai.starlake.sql

/** Scans past leading SQL trivia -- whitespace, unicode space separators, invisible format
  * characters, `--` line comments and (nested) `/* ... */` block comments -- so none of it can hide
  * the statement's real first token from a keyword-based check.
  *
  * Extracted from `ai.starlake.quack.edge.sql.LockdownScreen`, which lives under `quack.edge.sql`
  * and originally kept this scanner `private[sql]` for `CatalogWriteScreen`'s reuse. Moved here, to
  * the shared `ai.starlake.sql` package that already hosts `SqlCommentStripper`, so
  * `ai.starlake.quack.route.StatementClassifier` -- a different package tree entirely -- can reuse
  * the exact same scanner instead of growing a sibling hand-rolled one that would inevitably drift
  * from it. `LockdownScreen` and `CatalogWriteScreen` now call this object directly.
  */
object SqlTrivia:

  /** Skips leading whitespace (including BOM, zero-width space and unicode space separators), `--`
    * line comments, and (nested) block comments. An unterminated block comment consumes the rest of
    * the statement (nothing executable remains, so the empty remainder screens clean).
    */
  def stripLeading(s: String): String =
    var i     = 0
    var moved = true
    while moved do
      moved = false
      while i < s.length && isTriviaSpace(s(i)) do
        i += 1
        moved = true
      if i + 1 < s.length && s(i) == '-' && s(i + 1) == '-' then
        while i < s.length && s(i) != '\n' do i += 1
        moved = true
      else if i + 1 < s.length && s(i) == '/' && s(i + 1) == '*' then
        var depth = 1
        i += 2
        while i < s.length && depth > 0 do
          if i + 1 < s.length && s(i) == '/' && s(i + 1) == '*' then
            depth += 1
            i += 2
          else if i + 1 < s.length && s(i) == '*' && s(i + 1) == '/' then
            depth -= 1
            i += 2
          else i += 1
        if depth > 0 then i = s.length
        moved = true
    s.substring(i)

  // `Character.FORMAT` (Unicode category Cf) is the general class BOM (U+FEFF) and zero-width
  // space (U+200B) belong to, along with other invisible-but-not-whitespace characters such as the
  // word joiner (U+2060) -- confirmed separately against a real DuckDB to still execute with one
  // prepended, unlike Java's `isWhitespace`/`SPACE_SEPARATOR`, which both say no to it. Matching
  // the whole category rather than naming characters one at a time closes that gap and any sibling
  // Cf character, not just the ones already found.
  def isTriviaSpace(c: Char): Boolean =
    c.isWhitespace ||
      Character.getType(c) == Character.SPACE_SEPARATOR ||
      Character.getType(c) == Character.FORMAT
