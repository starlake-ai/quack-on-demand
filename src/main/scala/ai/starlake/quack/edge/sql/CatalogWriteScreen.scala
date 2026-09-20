package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, Verb}
import ai.starlake.quack.model.StatementKind
import com.typesafe.scalalogging.LazyLogging

/** Per-catalog write kill switch. Denies WRITE and DDL whose target sits in a catalog an operator
  * marked read-only (`qodstate_federated_source.read_only`), independently of the principal's
  * grants.
  *
  * This is defence in depth, NOT the primary gate. The primary gate is the ACL graph: a principal
  * without an RW or DDL grant on `sales_lake.*.*` cannot write there regardless of this flag,
  * because `PostgresAclValidator` already matches per-table verbs against the alias. This screen
  * exists so an operator can pin "QoD never writes to that catalog" without auditing every grant.
  *
  * Scope: this screen only ever has an opinion about WRITE and DDL statements. The caller passes
  * the already-computed `StatementKind`; a statement classified as anything other than `Dml`/`Ddl`
  * (a read, or a control-flow / unclassified statement) is admitted without being parsed here, even
  * when the SQL parser could not have resolved it -- this screen has nothing to say about reads, so
  * it does not risk denying one. This is NOT full parity with `PostgresAclValidator`'s fail-closed
  * conditions: the validator fails closed on every statement it cannot resolve regardless of verb,
  * this screen only on writes.
  *
  * For a write, while the caller reports any read-only catalog: the write must be FULLY resolvable
  * or it is denied fail-closed, because an unresolved target could be hiding a write to the
  * read-only catalog. That covers a parse failure, a qualification error (an unqualified name, or a
  * two-part name ambiguous against an attached catalog -- exactly the shape of a federated alias
  * reference), and an unsupported construct (a table function, a bare file reference) on the read
  * side of the write. Only a write that resolves in full is matched, verb by verb, against the
  * denied catalog set.
  *
  * When NOTHING is read-only the screen is inert and never parses, so it costs nothing on the
  * overwhelmingly common path.
  */
object CatalogWriteScreen extends LazyLogging:

  def screen(
      sql: String,
      kind: StatementKind,
      readOnlyCatalogs: Set[String],
      config: Config
  ): Option[String] =
    if readOnlyCatalogs.isEmpty then None
    else if kind != StatementKind.Dml && kind != StatementKind.Ddl then
      // Not a write: this screen has no business denying it, however the parser would have
      // fared. See the "Scope" scaladoc above.
      None
    else
      val denied     = readOnlyCatalogs.map(_.toLowerCase)
      val deniedList = denied.toList.sorted.mkString(", ")
      val result     = SqlParser.extract(sql, config)

      val parseFailures = result.statements.collect {
        case StatementResult.ParseError(_, snippet, msg) => s"$msg ($snippet)"
      }

      val qualificationErrors = result.statements.collect {
        case StatementResult.Extracted(_, _, _, q, _) if q.nonEmpty => q
      }.flatten

      val unsupportedConstructs = result.statements.collect {
        case StatementResult.Extracted(_, _, _, _, u) if u.nonEmpty => u
      }.flatten

      if parseFailures.nonEmpty then
        Some(
          "write statement could not be parsed while a read-only catalog " +
            s"($deniedList) is attached: ${parseFailures.head}"
        )
      else if qualificationErrors.nonEmpty || unsupportedConstructs.nonEmpty then
        val detail =
          (qualificationErrors.map(describe) ++ unsupportedConstructs).mkString("; ")
        Some(
          "write target could not be fully resolved while a read-only catalog " +
            s"($deniedList) is attached: $detail"
        )
      else
        val offending = result.statements.collect {
          case StatementResult.Extracted(_, _, accesses, _, _) =>
            accesses.collect {
              case a
                  if (a.verb == Verb.Write || a.verb == Verb.Ddl) &&
                    denied.contains(catalogOf(a.table.canonical)) =>
                a.table.canonical
            }
        }.flatten

        if offending.isEmpty then None
        else
          Some(
            s"catalog '${catalogOf(offending.head)}' is read-only on this deployment " +
              s"(write target: ${offending.toList.sorted.mkString(", ")})"
          )

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
