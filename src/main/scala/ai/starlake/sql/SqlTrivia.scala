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

  /** Every trivia character (see `isTriviaSpace`) replaced by an ASCII space, across the WHOLE
    * string, not just its head. Mirrors DuckDB's own parser front end, which substitutes its
    * Unicode space and format set with ASCII spaces before parsing -- which is why a non-breaking
    * space or a zero-width space BETWEEN two keywords is executable SQL, not just one sitting
    * before the first keyword. `stripLeading` only fixes the leading position; a scan that
    * terminates on `Character.isWhitespace` (e.g. `StatementClassifier.firstToken`) still misses an
    * interior occurrence, so the same hidden-verb bypass reappears one word to the right.
    *
    * SAFETY: the returned string is for CLASSIFICATION ONLY. The original `sql` string passed in
    * remains what is sent to the node and what every other consumer (audit, ACL, rewriters) sees --
    * never thread this normalized copy anywhere except into a classification scan.
    */
  def normalize(s: String): String =
    val chars = s.toCharArray
    var i     = 0
    while i < chars.length do
      if isTriviaSpace(chars(i)) then chars(i) = ' '
      i += 1
    new String(chars)

  /** The first token of `sql` as the ENGINE sees it: `--`/`/* */` comments and leading trivia
    * removed, remaining (interior) trivia normalized to ASCII spaces, then read up to the first
    * remaining whitespace character. Every first-token decision in the manager -- the router's
    * classifier, the two write-screens, the prepare-strategy chooser -- must go through this one
    * primitive, so the manager and DuckDB can never again disagree about where a statement's verb
    * ends: that disagreement, reproduced independently at each hand-rolled reader, is the exact
    * defect this arc closed three times over (BOM+ZWSP by name, then U+2060, then the whole `Cf`
    * category) and left open a fourth and fifth time in two readers this normalization never
    * reached (`FlightSqlRouter.stampPrelude`'s audit verb, `PrepareStrategy.choose`'s own reader).
    *
    * Deliberately minimal: it does NOT drop a leading `(`, does NOT stop at `;`, and does NOT
    * change case. Those are call-site extras, not universal to "what is the engine's first token"
    * -- e.g. `stampPrelude` wants the bare verb for a human-read audit log and never sees a leading
    * paren or an embedded `;`, while `StatementClassifier` and `PrepareStrategy` need both. Callers
    * fold their own `.dropWhile(_ == '(')` / `.takeWhile(_ != ';')` / `.toUpperCase` /
    * `.toLowerCase` onto the result instead.
    *
    * Comment REMOVAL (not substitution) is a distinct, separately-tracked gap: `SqlCommentStripper`
    * deletes a comment rather than replacing it with a separator, so `INSERT/*x*/INTO` still welds
    * into one token here, same as at every other consumer of `stripComments`. This primitive closes
    * the trivia half of the token-boundary problem, not that one.
    */
  def firstToken(sql: String): String =
    normalize(stripLeading(SqlCommentStripper.stripComments(sql))).takeWhile(!_.isWhitespace)
