package ai.starlake.quack.route

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.{SqlCommentStripper, SqlLexer, SqlTrivia}

/** Per-bucket keyword sets used to classify a statement by its first non-blank token. Sets are
  * uppercased on construction so matching is case-insensitive without per-call allocation.
  *
  * Operators tune this through `quack-on-demand.statementClassifier.*` (or the matching
  * `QOD_CLASSIFIER_*` env vars). Each conf key takes a comma-separated keyword list; whitespace is
  * trimmed. Values REPLACE the built-in defaults: to add a single keyword, copy the default list
  * and append.
  */
final case class StatementClassifierConfig(
    select: Set[String],
    dml: Set[String],
    ddl: Set[String],
    begin: Set[String],
    commit: Set[String],
    rollback: Set[String]
):
  /** Uppercased copy so the classifier matches case-insensitively without per-call allocation.
    * `normalized` is idempotent.
    */
  lazy val normalized: StatementClassifierConfig = copy(
    select = select.map(_.toUpperCase(Locale.ROOT)),
    dml = dml.map(_.toUpperCase(Locale.ROOT)),
    ddl = ddl.map(_.toUpperCase(Locale.ROOT)),
    begin = begin.map(_.toUpperCase(Locale.ROOT)),
    commit = commit.map(_.toUpperCase(Locale.ROOT)),
    rollback = rollback.map(_.toUpperCase(Locale.ROOT))
  )

object StatementClassifierConfig:

  /** Parse a comma-separated keyword list. Whitespace around each entry is trimmed. Empty entries
    * (consecutive commas, trailing comma) are dropped silently. A null or whitespace-only input
    * yields an empty set -- which collapses the matching arm to "never matches", making
    * misconfiguration fail-closed rather than fail-open.
    */
  def parseCsv(raw: String): Set[String] =
    if raw == null || raw.trim.isEmpty then Set.empty
    else raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet

  /** Built-in defaults baked into source. Edited by operators via the conf keys; tests pass these
    * directly.
    *
    * Buckets:
    *   - `select` - read-side verbs. `WITH` covers CTE-prefixed selects. `FROM` covers DuckDB /
    *     BigQuery FROM-first shorthand. `EXPLAIN` is read-side by convention (an EXPLAIN of an
    *     INSERT still doesn't mutate).
    *   - `dml` - write-side verbs. `COPY` covers DuckDB's data-load / data-export. `UPSERT` /
    *     `REPLACE` are dialect variants of INSERT-with-conflict-resolution.
    *   - `ddl` - schema-mutating. `ATTACH` / `DETACH` are DuckDB catalog manipulation;
    *     `COMMENT ON ...` is metadata DDL; `GRANT` / `REVOKE` mutate ACL state. `CHECKPOINT` runs
    *     DuckLake's maintenance chain on a DuckLake catalog (it merges and deletes data files), so
    *     it must reach a writer and be stamped and audited; while it is listed, `FORCE CHECKPOINT`
    *     and `CALL checkpoint(...)` / `CALL force_checkpoint(...)` classify with it.
    *   - `begin` - transaction start. `START TRANSACTION` is ANSI.
    *   - `commit` - transaction end. `END` is the Postgres alias.
    *   - `rollback` - transaction abort. `ABORT` is the Postgres alias.
    *
    * Anything not listed falls through to `StatementKind.Other`, which `RoleMatcher` routes like a
    * read.
    */
  val Defaults: StatementClassifierConfig = StatementClassifierConfig(
    select = Set("SELECT", "WITH", "VALUES", "SHOW", "DESCRIBE", "EXPLAIN", "FROM"),
    dml = Set("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "COPY"),
    ddl = Set(
      "CREATE",
      "DROP",
      "ALTER",
      "TRUNCATE",
      "ATTACH",
      "DETACH",
      "COMMENT",
      "GRANT",
      "REVOKE",
      "CHECKPOINT"
    ),
    begin = Set("BEGIN", "START"),
    commit = Set("COMMIT", "END"),
    rollback = Set("ROLLBACK", "ABORT")
  )

/** Cheap keyword-based classification of a SQL statement, used by the routing layer to pick a Quack
  * node role (READONLY / WRITEONLY / DUAL). Does NOT involve JSQLParser -- routing wants speed and
  * a coarse three-bucket answer; authorization runs `SqlParser.extract` separately and consumes its
  * own per-`TableAccess` `Verb` enum.
  *
  * SQL comments (`--`, `/* */`) are stripped, and every trivia character (whitespace, BOM,
  * zero-width space, non-breaking space, and other invisible `Character.FORMAT` / `SPACE_SEPARATOR`
  * characters `String.trim` and `Character.isWhitespace` don't treat as blank -- see
  * `ai.starlake.sql.SqlTrivia`) is normalized to an ASCII space ACROSS THE WHOLE STATEMENT, not
  * just its head, before the first-token match. A leading OR an interior trivia character (e.g.
  * `INSERT<NBSP>INTO t VALUES (1)`, which DuckDB executes identically to `INSERT INTO ...`) can
  * otherwise make a write look like `Other`, which `RoleMatcher` routes to a reader node and lets
  * skip `ProtectedWriteGuard` and the write audit path (the same class of bypass the `WITH` special
  * case below documents, reached here through a hidden first or verb token instead of a misleading
  * one). The normalized copy is used ONLY for classification -- `sql` itself is what every other
  * consumer, and the node, receives.
  */
final class StatementClassifier(
    config: StatementClassifierConfig = StatementClassifierConfig.Defaults
):

  private val cfg = config.normalized

  // Leading trivia/comments are stripped BEFORE stripComments, not after: `SqlTrivia.stripLeading`
  // tracks comment-nesting depth correctly, `SqlCommentStripper.stripComments` does not (it closes
  // on the FIRST `*/` it sees, however deep). DuckDB nests block comments to arbitrary depth
  // (verified against a real DuckDB 1.5.4: a leading `/* a /* b */ c */ INSERT INTO t VALUES (1)`
  // executes and writes the row), so stripping comments before leading trivia on a nested leading
  // comment exposes the literal text between the inner and outer close (`c` above) as if it were
  // the real first token -- a misclassification to `Other`, the same bucket `RoleMatcher` treats as
  // read-shaped, not merely a missed normalization. See `SqlTrivia.firstToken`'s scaladoc for the
  // identical reasoning; this entry point needs its own copy because `classifyStripped` below
  // receives already-comment-stripped text and reuses it for more than just the first token (the
  // `WITH`/`EXPLAIN` arms slice into it directly).
  //
  // `original` rides along untouched for the WITH and EXPLAIN arms, which lex the WHOLE statement
  // as DuckDB does instead of the comment-stripped, trivia-normalized copy: `SqlTrivia.normalize`
  // turns a NBSP inside a dollar tag into a space (`$<NBSP>$` stops being a tag) and stripping a
  // comment changes what DuckDB's unicode-space pre-pass keeps (see `SqlLexer`), so either copy can
  // open a literal DuckDB does not and hide a DML verb (or an EXPLAIN's ANALYZE) behind it.
  def classify(sql: String): StatementKind =
    classifyStripped(SqlCommentStripper.stripComments(SqlTrivia.stripLeading(sql)), sql)

  private def classifyStripped(raw: String, original: String): StatementKind =
    val sql = SqlTrivia.normalize(raw)
    firstToken(sql).map(_.toUpperCase(Locale.ROOT)) match
      // A WITH prefix says nothing about what the statement DOES: the verb after the
      // CTE list decides. First-token classification put WITH ... INSERT in the select
      // bucket, which routed the write to a reader node and let it skip
      // ProtectedWriteGuard, the RLS/CLS rewriters, author stamping, and the
      // data-write audit record. Only special-cased while WITH sits in the select
      // bucket, so an operator who reassigns it keeps full control.
      case Some("WITH") if cfg.select.contains("WITH") =>
        // DuckDB 2.0 allows DML inside a CTE body (WITH w AS (DELETE ... RETURNING *) SELECT ...),
        // so the verb after the list alone would misroute it as a read.
        val toks = wordTokens(original)
        if hasDmlKeyword(toks) then StatementKind.Dml
        else
          verbAfterWithClause(toks) match
            case Some(tok) =>
              kindOf(tok) match
                case StatementKind.Other => StatementKind.Select // unknown verb: legacy bucket
                case k                   => k
            case None => StatementKind.Select
      // An executing EXPLAIN (ANALYZE / ANALYSE, or an analyze option) RUNS the explained
      // statement; any other EXPLAIN only plans it. Classify the executed statement,
      // recursively so EXPLAIN ANALYZE WITH ... INSERT resolves too.
      case Some("EXPLAIN") if cfg.select.contains("EXPLAIN") =>
        executedByExplain(original) match
          case Some(stmt) => classify(stmt)
          case None       => StatementKind.Select
      // FORCE CHECKPOINT and CALL checkpoint(...) run a checkpoint like CHECKPOINT does (see the
      // `ddl` bucket's doc); only while CHECKPOINT sits in that bucket, so an operator who moves it
      // keeps control.
      case Some("FORCE" | "CALL") if cfg.ddl.contains("CHECKPOINT") && checkpoints(original) =>
        StatementKind.Ddl
      case Some(tok) => kindOf(tok)
      case None      => StatementKind.Other

  /** `FORCE CHECKPOINT ...`, or `CALL <name>(...)` naming `checkpoint` / `force_checkpoint` (bare,
    * quoted with either delimiter or qualified: DuckDB 2.0 runs `CALL system.main."checkpoint"()`),
    * read on DuckDB 2.0's tokens over the original text.
    */
  private def checkpoints(original: String): Boolean =
    val view                         = SqlLexer.engineView(original)
    val toks                         = SqlLexer.tokens(view).filterNot(_.isTrivia)
    def text(t: SqlLexer.Token)      = view.substring(t.start, t.end)
    def word(k: Int): Option[String] =
      toks.lift(k).filter(_.kind == SqlLexer.Kind.Word).map(text(_).toUpperCase(Locale.ROOT))
    def ident(t: SqlLexer.Token): Option[String] =
      if t.kind == SqlLexer.Kind.Word then Some(text(t).toLowerCase(Locale.ROOT))
      else if t.kind == SqlLexer.Kind.Literal && (view.charAt(t.start) == '"' ||
          view.charAt(t.start) == '`')
      then Some(ai.starlake.sql.QuotedIdentifier.unquote(text(t)).toLowerCase(Locale.ROOT))
      else None
    val open = toks.indexWhere(t => t.kind == SqlLexer.Kind.Punct && text(t) == "(")
    (word(0).contains("FORCE") && word(1).contains("CHECKPOINT")) ||
    (word(0).contains("CALL") && open >= 2 &&
      ident(toks(open - 1)).exists(StatementClassifier.CheckpointCalls))

  private def kindOf(tok: String): StatementKind =
    if cfg.select.contains(tok) then StatementKind.Select
    else if cfg.dml.contains(tok) then StatementKind.Dml
    else if cfg.ddl.contains(tok) then StatementKind.Ddl
    else if cfg.begin.contains(tok) then StatementKind.Begin
    else if cfg.commit.contains(tok) then StatementKind.Commit
    else if cfg.rollback.contains(tok) then StatementKind.Rollback
    else StatementKind.Other

  // Routes through the one shared reader (`SqlTrivia.firstToken`) rather than a hand-rolled
  // takeWhile of its own -- see that method's scaladoc for why a sibling reader is exactly how
  // this class of bug regenerates. `sql` here is already comment-stripped and whole-string
  // normalized by `classifyStripped`, so `firstToken`'s own stripComments/stripLeading/normalize
  // passes are idempotent no-ops on it; kept anyway so this method stays correct even if a future
  // caller passes it raw SQL directly. `.takeWhile(_ != ';')` and `.dropWhile(_ == '(')` are this
  // call site's own extras (a bare `COMMIT;` or a WITH-list's opening paren), not universal to the
  // shared primitive.
  private def firstToken(sql: String): Option[String] =
    val tok = SqlTrivia.firstToken(sql).takeWhile(_ != ';').dropWhile(_ == '(')
    Option.when(tok.nonEmpty)(tok)

  /** The statement an EXPLAIN EXECUTES, or None when it only plans it. DuckDB 2.0's grammar is
    * `'EXPLAIN' AnalyzeKeyword? ExplainOptionList? ExplainableStatements` with `AnalyzeKeyword <-
    * 'ANALYZE' / 'ANALYSE'` and `ExplainOptionList <- Parens(List(ExplainOptionName Expression?))`,
    * and its transformer turns the statement into EXPLAIN ANALYZE for the keyword and for an option
    * NAMED analyze (any value, ignored; a quoted name is case-folded, so `("analyze")` executes
    * too). Each of `EXPLAIN ANALYSE DELETE ...`, `EXPLAIN (ANALYZE) DELETE ...` and
    * `EXPLAIN (FORMAT json, ANALYZE true) DELETE ...` deletes the rows on the 2.0 CLI, while
    * `EXPLAIN DELETE ...` and `EXPLAIN (FORMAT json) DELETE ...` delete nothing.
    *
    * Read through `SqlLexer` over the ORIGINAL text, so an ANALYZE inside a literal or a comment is
    * not one and a literal cannot hide one. A parenthesized group right after the keyword is an
    * option list when its first token can name an option that runs (`ANALYZE`, `ANALYSE`, `FORMAT`,
    * `SQL`, or a quoted identifier, which may fold to any of them); any other option name makes
    * DuckDB refuse the statement, so every other group is the explained statement itself (`EXPLAIN
    * ANALYZE (SELECT ...)`). Fail closed inside the list: any ANALYZE word, or quoted identifier
    * folding to one, counts, even in a value position. The explained statement is cut from
    * `SqlLexer.engineView`, which lexes alone exactly as it lexed in place. A statement whose first
    * token, as DuckDB reads it, is not EXPLAIN (a leading U+00AD, a leading paren) is one DuckDB
    * refuses, and is answered None.
    */
  private def executedByExplain(sql: String): Option[String] =
    val view                                         = SqlLexer.engineView(sql)
    val toks                                         = SqlLexer.tokens(view).filterNot(_.isTrivia)
    def text(t: SqlLexer.Token): String              = view.substring(t.start, t.end)
    def upper(t: SqlLexer.Token): String             = text(t).toUpperCase(Locale.ROOT)
    def isPunct(t: SqlLexer.Token, c: Char): Boolean =
      t.kind == SqlLexer.Kind.Punct && view.charAt(t.start) == c
    def isWord(t: SqlLexer.Token, words: Set[String]): Boolean =
      t.kind == SqlLexer.Kind.Word && words.contains(upper(t))
    def isQuotedIdent(t: SqlLexer.Token): Boolean =
      val q = view.charAt(t.start)
      t.kind == SqlLexer.Kind.Literal && (q == '"' || q == '`')
    def unquoted(t: SqlLexer.Token): String =
      val q = view.charAt(t.start).toString
      text(t).drop(1).stripSuffix(q).replace(q + q, q).toUpperCase(Locale.ROOT)
    def isAnalyzeName(t: SqlLexer.Token): Boolean =
      isWord(t, StatementClassifier.AnalyzeKeywords) ||
        (isQuotedIdent(t) && StatementClassifier.AnalyzeKeywords.contains(unquoted(t)))
    if toks.isEmpty || !isWord(toks.head, Set("EXPLAIN")) then None
    else
      val keyword      = toks.length > 1 && isWord(toks(1), StatementClassifier.AnalyzeKeywords)
      val afterKeyword = if keyword then 2 else 1
      val optionList   =
        afterKeyword + 1 < toks.length && isPunct(toks(afterKeyword), '(') && {
          val first = toks(afterKeyword + 1)
          isWord(first, StatementClassifier.RunnableExplainOptions) || isQuotedIdent(first)
        }
      // index of the token closing the option list (or the end of input, unbalanced)
      val close =
        if !optionList then afterKeyword
        else
          var depth = 0
          var k     = afterKeyword
          var found = -1
          while found < 0 && k < toks.length do
            if isPunct(toks(k), '(') then depth += 1
            else if isPunct(toks(k), ')') then
              depth -= 1
              if depth == 0 then found = k
            k += 1
          if found < 0 then toks.length else found
      val analyzeOption =
        optionList && toks.slice(afterKeyword + 1, close).exists(isAnalyzeName)
      val stmtAt = if optionList then close + 1 else afterKeyword
      Option.when(keyword || analyzeOption)(
        if stmtAt < toks.length then view.substring(toks(stmtAt).start) else ""
      )

  /** The first keyword token at the leading WITH's depth after its CTE list, uppercased: the
    * statement's real verb. Read through [[wordTokens]], so parens or verbs inside a CTE body's
    * literals cannot desync it. The scan starts at the first `WITH` word (the statement's own, or
    * the one after `EXPLAIN ANALYZE`). CTE names, AS, RECURSIVE, MATERIALIZED and column lists are
    * all either non-keyword words or parenthesized groups, so the first token that matches a
    * configured bucket IS the verb (reserved words cannot be unquoted CTE names). None when the
    * scan runs out, which the caller maps to the legacy select bucket.
    */
  private def verbAfterWithClause(toks: Vector[StatementClassifier.WordToken]): Option[String] =
    // depth relative to the leading WITH, which a parenthesized statement opens at depth 1
    val fromWith = toks.dropWhile(_.upper != "WITH")
    fromWith.headOption.flatMap(w =>
      fromWith.iterator
        .drop(1)
        .find(t => t.depth == w.depth && kindOf(t.upper) != StatementKind.Other)
        .map(_.upper)
    )

  /** True when any word of a WITH statement, outside literals, quoted identifiers and comments and
    * at any paren depth, is a configured DML keyword or `TRUNCATE`: a data-modifying CTE (DuckDB
    * 2.0, `WITH w AS (DELETE ... RETURNING *) SELECT ...`), including one behind a CTE body's own
    * nested WITH, is a write whatever verb follows the CTE list. `TRUNCATE` joins the configured
    * DML set here because DuckDB 2.0 accepts it as a CTE body and runs it as a DELETE (`WITH w AS
    * (TRUNCATE t) SELECT 1` empties `t`); the rest of the DDL bucket stays out (`comment` is a
    * common column name, and CREATE / DROP / CHECKPOINT / MERGE bodies are parse errors on 2.0).
    * Deliberately fail closed: it does not try to tell a CTE body's verb from the same word used
    * elsewhere, so a read that carries one of these keywords as a bare word (a column named `copy`,
    * `update`, `delete` or `truncate`, say) is a false positive. A false positive is not free: the
    * read routes to a writer, is author-stamped, switches prepare to SkipExecute and is refused for
    * a branch-only token on a main pool. The one carve-out is `REPLACE` immediately followed (after
    * optional whitespace or comments) by `(`, which is DuckDB's string function (and the `SELECT *
    * REPLACE (...)` star modifier), not a write verb. `COPY (SELECT ...) TO ...` still counts.
    */
  private def hasDmlKeyword(toks: Vector[StatementClassifier.WordToken]): Boolean =
    toks.exists(t =>
      (cfg.dml.contains(t.upper) || t.upper == "TRUNCATE") &&
        !(t.upper == "REPLACE" && t.beforeParen)
    )

  /** The word tokens of `sql` (the ORIGINAL statement, comments and trivia intact), uppercased,
    * each with its paren depth and whether the next token past whitespace and comments is `(`.
    * Every boundary comes from `ai.starlake.sql.SqlLexer`, the one reader shared with
    * `SqlCommentStripper` and `ai.starlake.quack.edge.sql.StatementHead.split` and ported from
    * DuckDB 2.0's own front end; this class keeps no quote rules or character classes of its own,
    * because a sibling reader with its own is how a literal one reader honours and another does not
    * hides a verb. Literals, quoted identifiers, comments and numbers are not words.
    */
  private def wordTokens(sql: String): Vector[StatementClassifier.WordToken] =
    val toks  = SqlLexer.tokens(sql)
    val out   = Vector.newBuilder[StatementClassifier.WordToken]
    var depth = 0
    var k     = 0
    while k < toks.length do
      val t = toks(k)
      if t.kind == SqlLexer.Kind.Punct && sql.charAt(t.start) == '(' then depth += 1
      else if t.kind == SqlLexer.Kind.Punct && sql.charAt(t.start) == ')' then depth -= 1
      else if t.kind == SqlLexer.Kind.Word then
        var m = k + 1
        while m < toks.length && toks(m).isTrivia do m += 1
        val beforeParen =
          m < toks.length && toks(m).kind == SqlLexer.Kind.Punct && sql.charAt(toks(m).start) == '('
        out += StatementClassifier.WordToken(
          sql.substring(t.start, t.end).toUpperCase(Locale.ROOT),
          depth,
          beforeParen
        )
      k += 1
    out.result()

object StatementClassifier:

  /** One word of a statement outside literals: uppercased text, paren depth, and whether the next
    * non-blank character is `(`.
    */
  private final case class WordToken(upper: String, depth: Int, beforeParen: Boolean)

  /** The table functions that run a checkpoint, as `CALL` names them. */
  private val CheckpointCalls: Set[String] = Set("checkpoint", "force_checkpoint")

  /** DuckDB 2.0's `AnalyzeKeyword <- 'ANALYZE' / 'ANALYSE'`. */
  private val AnalyzeKeywords: Set[String] = Set("ANALYZE", "ANALYSE")

  /** The EXPLAIN option names DuckDB 2.0's transformer accepts (`format`, `sql`, `analyze`, the
    * last also spelled through `AnalyzeKeyword`); any other name refuses the statement.
    */
  private val RunnableExplainOptions: Set[String] = AnalyzeKeywords ++ Set("FORMAT", "SQL")

  /** Default classifier instance backed by [[StatementClassifierConfig.Defaults]]. Used by tests
    * and by call sites that haven't been threaded with a config-driven instance.
    */
  val default: StatementClassifier = new StatementClassifier(StatementClassifierConfig.Defaults)

  /** Convenience delegating to [[default]] so static-method call sites
    * (`StatementClassifier.classify(sql)`) keep working. Production wiring should construct a
    * config-driven instance via `Main.scala` and pass it through `FlightSqlRouter` to use the
    * operator's tuned keyword lists.
    */
  def classify(sql: String): StatementKind = default.classify(sql)
