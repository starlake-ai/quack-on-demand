package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{SqlParser, StatementResult, Verb}
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
  * Fail-closed, matching [[PostgresAclValidator]]: while any read-only catalog is attached, a
  * statement the parser cannot resolve is denied rather than admitted. When NOTHING is read-only
  * the screen is inert and never parses, so it costs nothing on the overwhelmingly common path.
  */
object CatalogWriteScreen extends LazyLogging:

  def screen(sql: String, readOnlyCatalogs: Set[String], config: Config): Option[String] =
    if readOnlyCatalogs.isEmpty then None
    else
      val denied = readOnlyCatalogs.map(_.toLowerCase)
      val result = SqlParser.extract(sql, config)

      val parseFailures = result.statements.collect {
        case StatementResult.ParseError(_, snippet, msg) => s"$msg ($snippet)"
      }

      if parseFailures.nonEmpty then
        Some(
          "statement could not be parsed while a read-only catalog " +
            s"(${denied.toList.sorted.mkString(", ")}) is attached: ${parseFailures.head}"
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

  /** First segment of a canonical `db.schema.table` ref, already lowercased by the parser. */
  private def catalogOf(canonical: String): String =
    val i = canonical.indexOf('.')
    if i < 0 then canonical else canonical.substring(0, i)
