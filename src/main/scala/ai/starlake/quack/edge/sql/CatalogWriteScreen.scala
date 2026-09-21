package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableAccess, Verb}
import ai.starlake.quack.model.StatementKind
import ai.starlake.sql.SqlCommentStripper
import com.typesafe.scalalogging.LazyLogging

/** Per-catalog write kill switch. Denies WRITE and DDL whose target sits in a catalog an operator
  * marked read-only (`qodstate_federated_source.read_only`), independently of the principal's
  * grants.
  *
  * This is defence in depth, NOT the primary gate. The primary gate is the ACL graph: a principal
  * without an RW or DDL grant on `sales_lake.*.*` cannot write there regardless of this flag,
  * because `PostgresAclValidator` already matches per-table verbs against the alias. This screen
  * adds a second, narrower guarantee that does not depend on any grant being configured correctly.
  *
  * Scope and the rule this screen actually enforces, precisely, because an earlier version of this
  * doc conflated "the classifier says this isn't a write" with "this is a read" and that conflation
  * was a bypass:
  *
  *   1. When no catalog on the pool is read-only: inert, never parses.
  *   2. Otherwise the submission is split into top-level statements (the same quote- and
  *      comment-aware splitter `LockdownScreen` uses). If EVERY fragment classifies as a
  *      positively-known read-side `StatementKind` (`Select`, `Begin`, `Commit`, `Rollback` -- NOT
  *      `Other`), the whole submission is admitted without being parsed. This is the only cheap
  *      path, and it is judged per fragment, not by the first token of the submission: a batch
  *      whose first statement is a `SELECT` and whose second is an `INSERT` into the read-only
  *      catalog does NOT take this path. Note this path DOES trust the classifier's `select` /
  *      `begin` / `commit` / `rollback` buckets, which are as operator-tunable as any other bucket:
  *      an operator who moves a write verb into `select` disables the screen for that verb without
  *      it ever reaching step 3.
  *   3. Otherwise the submission is parsed once with `SqlParser.extract`. `SqlParser.extract`
  *      itself has two shapes of failure, and the screen treats them differently:
  *      - If jsqlparser can split the submission into one node per statement, each result is judged
  *        on its own, using the `StatementKind` of ITS OWN snippet (not the submission's) -- see
  *        rule 4 below.
  *      - If jsqlparser THROWS on the whole submission (e.g. `START TRANSACTION; INSERT ...;
  *        COMMIT`, `CHECKPOINT; INSERT ...`), `SqlParser.extract`'s outer catch collapses the batch
  *        into a SINGLE `ParseError` whose snippet is the ENTIRE submission -- there is no
  *        per-statement snippet to judge. Trusting that collapsed snippet with rule 4 would reduce
  *        to classifying the whole submission by its first token, which is the exact bypass this
  *        screen exists to close. Detected by `result.statements.length < fragments.length`: the
  *        screen falls back to judging the FRAGMENT list instead, and denies the whole submission
  *        if ANY fragment looks write-shaped (rule 4's own `isWriteShaped`, applied per fragment).
  *        Otherwise it admits, so a batch that merely happens to collapse (e.g. `BEGIN; PRAGMA
  *        database_list; COMMIT`, which jsqlparser also swallows into one blank node) is not
  *        blanket-denied just because it collapsed.
  *   4. The per-statement rule, for a statement with its own trustworthy snippet:
  *      - a statement that classifies `Dml` or `Ddl` is judged FAIL CLOSED: a parse error on it, a
  *        qualification error on it, an unsupported ref on it, or a resolved `Write`/`Ddl` access
  *        it produces against a read-only catalog, are all denied.
  *      - a statement that classifies anything else (`Select`/`Begin`/`Commit`/`Rollback` reaching
  *        this arm because another fragment in the batch was not read-side, or `Other`) is denied
  *        ONLY when it produces a RESOLVED `Write`/`Ddl` access against a read-only catalog, OR
  *        when its own first token is `PREPARE` or `EXECUTE` (see the note on `isWriteShaped`
  *        below). Any other parse error, a qualification error, or an unsupported ref on such a
  *        statement is ADMITTED -- this is what keeps `PRAGMA table_info(...)`, `CALL some_udf()`,
  *        and other DuckDB-native syntax the parser has no arm for from being denied just because a
  *        read-only catalog is attached somewhere on the pool. (`SHOW ALL TABLES` is a read-side
  *        example too, but it never reaches this rule: `SHOW` sits in the classifier's `select`
  *        bucket, so it is admitted at step 2 without being parsed at all.)
  *
  * The load-bearing consequence of rule 4's second bullet: for a statement that reaches step 3/4
  * with its OWN snippet, this screen does NOT trust the classifier's `dml`/`ddl` buckets as the
  * sole security boundary -- see below. It DOES still trust the classifier for the cheap path (step
  * 2) and for detecting a collapsed batch's write-shaped fragments (step 3's second bullet),
  * because neither of those has a parsed access set to fall back on; only the parsed, per-statement
  * path can out-rule the classifier. `StatementClassifier`'s buckets are operator-tunable
  * (`QOD_CLASSIFIER_*`) and its catch-all `Other` is documented as "treated like a read by default"
  * for ROUTING purposes -- but a statement the classifier cannot recognize is not a statement this
  * screen has proven is a read, it is a statement nobody looked at. Every write step 3/4 can
  * resolve is still checked against the read-only set regardless of how it classifies; only a write
  * it genuinely cannot resolve, and cannot therefore prove targets the read-only catalog, is let
  * through. Concretely: emptying the `dml` bucket (or dropping `INSERT` from it) does not disable
  * this screen for a resolvable `INSERT` -- it still parses, still resolves a `Write` access, and
  * is still denied. It only widens what counts as unresolvable fail-open bait, which was already
  * the classifier's `Other` bucket's job before this screen existed.
  *
  * `PREPARE ... AS <write>; EXECUTE ...` composes into an executed write while neither statement
  * classifies `Dml`/`Ddl` (`PREPARE`/`EXECUTE` sit in no `StatementClassifier` bucket) and neither
  * resolves through `SqlParser` (it has no arm for either node type, so both come back as
  * `ParseError`). The precise fix -- a `SqlParser` arm that recurses into the prepared statement,
  * mirroring the existing `ExplainStatement` arm -- is out of this screen's reach. Instead
  * `isWriteShaped` treats a snippet whose first token is `PREPARE` or `EXECUTE` as write-shaped
  * unconditionally, the same blunt fail-closed treatment `ATTACH`/`COPY`/`GRANT` already get from
  * their classifier buckets. Cost: an `EXECUTE` of a genuinely read-only prepared statement is
  * denied too while any catalog is read-only.
  *
  * The blank-snippet rule -- a blank snippet is ALWAYS treated as write-shaped, regardless of what
  * the statement actually was -- is the other place this screen deliberately widens past what it
  * can prove, and it applies ONLY on the per-statement path (rule 4), when jsqlparser returned one
  * node per statement and this particular one's own snippet came back empty. jsqlparser's
  * `UnsupportedStatement` (the node `Feature.allowUnsupportedStatements` produces for a statement
  * it cannot parse at all) has an empty `toString`, so a blank snippet is not evidence the
  * statement was harmless, it is the parser giving up. Most DuckDB-native syntax the parser cannot
  * handle still comes back non-blank (`SHOW ALL TABLES`, `PRAGMA ...`, and other
  * `UnsupportedStatement`s all render their original text) or is read-side and never reaches the
  * parser via step 2, so this rule rarely fires on a genuine read -- e.g.
  * `INSERT INTO sales_lake.main.orders VALUES (1` (an unbalanced literal, submitted alone) parses
  * to one blank `UnsupportedStatement` and is denied by this rule alone. It does NOT reach a
  * submission that collapses into fewer nodes than fragments (step 3's second bullet handles that
  * case first, over the fragment list, before this rule ever sees a snippet) --
  * `BEGIN; PRAGMA database_list; COMMIT` also collapses to a single blank node, but because none of
  * its three fragments classify write-shaped, step 3's fallback admits it without this rule ever
  * running.
  *
  * This is NOT full parity with `PostgresAclValidator`'s fail-closed conditions: the validator
  * fails closed on every statement it cannot resolve regardless of verb; this screen only on
  * statements that either classify as a write or resolve to one. And it has nothing to say about
  * statements that never touch a table at all (`ATTACH`, `DETACH`, `CREATE SECRET`, `COPY`,
  * `GRANT`) beyond what step 3 already implies: `SqlParser` has no arm for any of them, so under
  * step 3's first bullet a `Ddl`/`Dml`-classified one of these is denied (`ATTACH`/`DETACH`/
  * `GRANT`/`REVOKE`/`COMMENT` classify `Ddl`; `COPY` classifies `Dml`) the moment ANY catalog on
  * the pool is read-only, regardless of which catalog it targets -- marking one federated source
  * read-only starts refusing these pool-wide, not just against that source. The same fail-closed
  * widening applies to any two-part write against a DIFFERENT, writable, attached catalog: the
  * parser cannot tell a catalog head from a schema head there (`AmbiguousCatalogRef`), so
  * `INSERT INTO acme_db.orders VALUES (1)` is refused as unresolvable once ANY catalog on the pool
  * is read-only, even though `acme_db` itself is not. This is intentional fail-closed behaviour,
  * not a bug, but an operator flipping `read_only` on one source needs to expect it pool-wide.
  *
  * When NOTHING is read-only the screen is inert and never parses, so it costs nothing on the
  * overwhelmingly common path.
  */
object CatalogWriteScreen extends LazyLogging:

  private val ReadSideKinds: Set[StatementKind] =
    Set(StatementKind.Select, StatementKind.Begin, StatementKind.Commit, StatementKind.Rollback)

  /** `classify` is threaded through rather than a single precomputed `StatementKind`, because this
    * screen must classify each statement of the submission on its own snippet, not the submission
    * as a whole -- see the scaladoc above. Callers pass `StatementClassifier#classify` (the
    * operator-configured instance the router already has, not a fixed default).
    */
  def screen(
      sql: String,
      classify: String => StatementKind,
      readOnlyCatalogs: Set[String],
      config: Config
  ): Option[String] =
    if readOnlyCatalogs.isEmpty then None
    else
      val fragments = LockdownScreen.splitStatements(sql)
      if fragments.forall(f => ReadSideKinds.contains(classify(f))) then
        // Cheap path: no fragment of the batch is even shaped like a write, so nothing to parse.
        // An empty fragment list (blank submission) also lands here vacuously.
        None
      else
        val denied     = readOnlyCatalogs.map(_.toLowerCase)
        val deniedList = denied.toList.sorted.mkString(", ")
        val result     = SqlParser.extract(sql, config)

        // `PREPARE p AS INSERT ...; EXECUTE p` composes into an executed write without either
        // statement ever classifying Dml/Ddl or resolving through SqlParser (neither node type has
        // an arm there) -- see the scaladoc's PREPARE/EXECUTE paragraph. Recognized purely by first
        // token, independently of the classifier's tunable buckets. `normalized` is checked
        // against an ALREADY-normalized snippet (see `isWriteShaped`) rather than re-stripping
        // here, so this and the `classify` arm below judge the exact same head.
        def isPrepareOrExecute(normalized: String): Boolean =
          val head = normalized.takeWhile(c => !c.isWhitespace && c != ';').toUpperCase
          head == "PREPARE" || head == "EXECUTE"

        // jsqlparser's `UnsupportedStatement` (the node `Feature.allowUnsupportedStatements`
        // produces for a statement it cannot parse at all) has an empty `toString`, and
        // `sqlSnippet` is built from `stmt.toString`. A blank snippet is therefore not evidence of
        // anything -- it is the parser giving up on the ORIGINAL text, which may well have been a
        // write. Fail closed rather than let an empty string classify as `Other` and be admitted.
        // The blank check runs on the RAW snippet: normalizing first would turn a comment-only
        // fragment into an empty string too, and that is already handled by the check itself, so
        // normalizing before it would only cost the distinction for no benefit.
        //
        // Every other check normalizes the snippet ONCE, up front, by stripping comments (the same
        // `SqlCommentStripper` `StatementClassifier.classify` uses) and then leading trivia (the
        // same `LockdownScreen.stripLeadingTrivia` scanner `LockdownScreen` uses to the same end,
        // widened to `private[sql]` for this reuse). A raw `.trim` alone only removes characters
        // `<= U+0020`; it lets a leading NBSP, BOM, zero-width space, or `/*x*/`/`--` comment hide
        // the first token from BOTH `isPrepareOrExecute` and `classify`'s own first-token read,
        // admitting an invisible-character-prefixed `INSERT`/`PREPARE`/etc as `Other`. Both arms
        // MUST see the same normalized snippet -- normalizing only one leaves the other's blind
        // spot open, which is exactly how the previous fix (comments only, no leading trivia)
        // still admitted a plain write behind one invisible character.
        def isWriteShaped(snippet: String): Boolean =
          if snippet.isBlank then true
          else
            val normalized =
              LockdownScreen.stripLeadingTrivia(SqlCommentStripper.stripComments(snippet))
            if isPrepareOrExecute(normalized) then true
            else
              classify(normalized) match
                case StatementKind.Dml | StatementKind.Ddl => true
                case _                                     => false

        def offendingIn(accesses: Set[TableAccess]): Set[String] =
          accesses.collect {
            case a
                if (a.verb == Verb.Write || a.verb == Verb.Ddl) &&
                  denied.contains(catalogOf(a.table.canonical)) =>
              a.table.canonical
          }

        def denialFor(stmt: StatementResult): Option[String] = stmt match
          case StatementResult.ParseError(_, snippet, msg) =>
            if isWriteShaped(snippet) then
              Some(
                "write statement could not be parsed while a read-only catalog " +
                  s"($deniedList) is attached: $msg ($snippet)"
              )
            else None
          case StatementResult.Extracted(_, snippet, accesses, q, u) =>
            if isWriteShaped(snippet) && (q.nonEmpty || u.nonEmpty) then
              val detail = (q.map(describe) ++ u).mkString("; ")
              Some(
                "write target could not be fully resolved while a read-only catalog " +
                  s"($deniedList) is attached: $detail"
              )
            else
              val offending = offendingIn(accesses)
              if offending.isEmpty then None
              else
                Some(
                  s"catalog '${catalogOf(offending.head)}' is read-only on this deployment " +
                    s"(write target: ${offending.toList.sorted.mkString(", ")})"
                )
          case StatementResult.ControlFlow(_, _, _) => None

        if result.statements.length < fragments.length then
          // `SqlParser.extract` collapsed the whole submission into fewer StatementResults than
          // there are top-level fragments -- jsqlparser threw on the SUBMISSION rather than on one
          // statement, and `SqlParser.extract`'s outer catch reports that as a SINGLE ParseError
          // whose snippet is the entire submission text (see the scaladoc's step 3). That collapsed
          // snippet cannot be handed to `isWriteShaped`: doing so would classify the whole
          // submission by its first token, exactly the bypass this screen exists to close. Fall
          // back to the fragment list the splitter already produced above instead, and fail closed
          // if ANY fragment looks write-shaped on its own.
          if fragments.exists(isWriteShaped) then
            Some(
              "write statement could not be parsed while a read-only catalog " +
                s"($deniedList) is attached and the submission could not be split into " +
                "individually verifiable statements"
            )
          else None
        else result.statements.iterator.flatMap(denialFor).nextOption()

  /** Human-readable rendering of a qualification failure, matching `PostgresAclValidator`'s wording
    * for the same `DenyReason` so an operator sees one consistent message across both gates.
    */
  private def describe(reason: DenyReason): String = reason match
    case DenyReason.AmbiguousCatalogRef(tableName, catalog) =>
      s"ambiguous two-part name '$tableName': '$catalog' is an attached catalog; " +
        s"qualify fully as '$catalog.<schema>.<table>'"
    case DenyReason.UnqualifiedTable(tableName, missingPart) =>
      s"unqualified table '$tableName' (missing $missingPart)"
    case DenyReason.ParseError(message) => message

  /** First segment of a canonical `db.schema.table` ref, already lowercased by the parser. */
  private def catalogOf(canonical: String): String =
    val i = canonical.indexOf('.')
    if i < 0 then canonical else canonical.substring(0, i)
