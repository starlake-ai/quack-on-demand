package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import cats.effect.IO
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.Select

import scala.util.{Failure, Success, Try}

/** Resolves the schema/catalog defaults in scope when parsing a SQL statement so the rewriter can
  * qualify bare table references (`customer` -> `acme_tpch.tpch1.customer`).
  */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema:   Option[String]
)

object ColumnPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  final case class Denied(reason: String) extends Outcome
  case object Passthrough                  extends Outcome

/** Rewrites SELECT statements to enforce column-level policies carried in the caller's
  * [[EffectiveSet]]. Non-SELECT statements, superusers, users with no column policies, and
  * unparseable SQL all pass through unchanged (the query will succeed or fail on its own merit).
  * Actual column rewriting is added in Tasks 12-14.
  */
final class ColumnPolicyRewriter(catalog: ColumnCatalog):
  import ColumnPolicyRewriter._

  def rewrite(
      sql:  String,
      kind: StatementKind,
      eff:  EffectiveSet,
      ctx:  SchemaContext
  ): IO[Outcome] = IO.defer {
    // Cheapest exits first.
    if eff.user.tenant.isEmpty then IO.pure(Passthrough)   // superuser
    else if kind != StatementKind.Select then IO.pure(Passthrough)
    else if eff.columnPolicies.isEmpty then IO.pure(Passthrough)
    else
      Try(CCJSqlParserUtil.parse(sql)) match
        case Failure(_)         => IO.pure(Passthrough)    // unparseable: let the node handle it
        case Success(_: Select) => IO.pure(Passthrough)    // TODO Task 12+: actually rewrite
        case Success(_)         => IO.pure(Passthrough)
  }