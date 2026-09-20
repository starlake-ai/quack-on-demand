package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableAccess, Verb}
import ai.starlake.quack.model.StatementKind
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
  *      catalog does NOT take this path.
  *   3. Otherwise the submission is parsed once with `SqlParser.extract`, and each statement in the
  *      result is judged on its own, using the `StatementKind` of ITS OWN snippet (not the
  *      submission's):
  *      - a statement that classifies `Dml` or `Ddl` is judged FAIL CLOSED: a parse error on it, a
  *        qualification error on it, an unsupported ref on it, or a resolved `Write`/`Ddl` access
  *        it produces against a read-only catalog, are all denied.
  *      - a statement that classifies anything else (`Select`/`Begin`/`Commit`/`Rollback` reaching
  *        this arm because another fragment in the batch was not read-side, or `Other`) is denied
  *        ONLY when it produces a RESOLVED `Write`/`Ddl` access against a read-only catalog. A
  *        parse error, a qualification error, or an unsupported ref on such a statement is ADMITTED
  *        -- this is what keeps `PRAGMA`, `SHOW ALL TABLES`, and other DuckDB-native syntax the
  *        parser has no arm for from being denied just because a read-only catalog is attached
  *        somewhere on the pool.
  *
  * The load-bearing consequence of step 3's second bullet: this screen does NOT trust the
  * classifier's keyword buckets as a security boundary. `StatementClassifier`'s buckets are
  * operator-tunable (`QOD_CLASSIFIER_*`) and its catch-all `Other` is documented as "treated like a
  * read by default" for ROUTING purposes -- but a statement the classifier cannot recognize is not
  * a statement this screen has proven is a read, it is a statement nobody looked at. Every write
  * this screen can resolve is still checked against the read-only set regardless of how it
  * classifies; only a write it genuinely cannot resolve, and cannot therefore prove targets the
  * read-only catalog, is let through. Concretely: emptying the `dml` bucket (or dropping `INSERT`
  * from it) does not disable this screen for a resolvable `INSERT` -- it still parses, still
  * resolves a `Write` access, and is still denied. It only widens what counts as unresolvable
  * fail-open bait, which was already the classifier's `Other` bucket's job before this screen
  * existed.
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

        // jsqlparser's `UnsupportedStatement` (the node `Feature.allowUnsupportedStatements`
        // produces for a statement it cannot parse at all) has an empty `toString`, and
        // `sqlSnippet` is built from `stmt.toString`. A blank snippet is therefore not evidence of
        // anything -- it is the parser giving up on the ORIGINAL text, which may well have been a
        // write. Fail closed rather than let an empty string classify as `Other` and be admitted.
        def isWriteShaped(snippet: String): Boolean =
          if snippet.isBlank then true
          else
            classify(snippet) match
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

        result.statements.iterator.flatMap(denialFor).nextOption()

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
