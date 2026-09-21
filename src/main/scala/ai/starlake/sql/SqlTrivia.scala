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
        // A `--` comment ends at a line feed OR at a bare carriage return: DuckDB accepts both as
        // terminators (verified against a real DuckDB 1.5.4 -- `-- x\rINSERT INTO t VALUES (1)`
        // writes the row, and the `\r` form of a DROP drops the table). Scanning to `\n` alone
        // swallowed the whole statement, so the verb behind the `\r` never reached the first-token
        // read and the write classified `Other`: routed to a reader node, no ProtectedWriteGuard,
        // no author stamp, no write audit record. The terminator is left in place for the trivia
        // arm above to consume on the next pass.
        while i < s.length && s(i) != '\n' && s(i) != '\r' do i += 1
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
    * Composed as `stripLeading -> stripComments -> normalize`. Each pass covers something the other
    * two do not, which is why all three are here:
    *
    *   - `stripLeading` is the only one that DELETES leading Unicode trivia. `normalize` rewrites
    *     such a character to an ASCII space IN PLACE, and an ASCII space at index 0 stops a
    *     `takeWhile` just as dead as the original NBSP did; `stripComments` does not touch it at
    *     all. Mutation-tested: dropping this pass flips the "verb hidden behind a leading NBSP"
    *     cases straight back to an empty first token.
    *   - `stripComments` is the only one that removes an INTERIOR comment, and the only one that
    *     knows DuckDB's quoting forms. It now tracks block-comment nesting depth as well, so this
    *     order no longer depends on `stripLeading` reaching a nested LEADING comment first; the two
    *     agree on that shape rather than one rescuing the other. Keeping `stripLeading` in front is
    *     still right (it is the pass that must see index 0 untouched) and it keeps the leading
    *     position covered by two independent scanners rather than one.
    *   - `normalize` runs LAST because `stripComments` can re-expose an interior trivia character
    *     that was sitting next to a comment body.
    *
    * DuckDB nests block comments to arbitrary depth (verified against a real DuckDB 1.5.4:
    * `/* a /* b */ c */ INSERT INTO t VALUES (1)` executes and writes the row, and so does three
    * levels deep). A scanner that closes on the first closing marker exposes the text between the
    * inner and outer close (`c` in the example) where the next keyword should be -- not merely "no
    * discrimination" but a MISCLASSIFICATION, since `c` is not a keyword and lands in `Other`, the
    * bucket every gate treats as read-shaped / not-proven-a-write.
    */
  def firstToken(sql: String): String =
    normalize(SqlCommentStripper.stripComments(stripLeading(sql))).takeWhile(!_.isWhitespace)
