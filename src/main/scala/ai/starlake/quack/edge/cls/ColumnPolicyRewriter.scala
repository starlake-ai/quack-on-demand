package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import cats.effect.IO
import cats.syntax.traverse._
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{ParenthesedSelect, PlainSelect, Select, SetOperationList}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Resolves the schema/catalog defaults in scope when parsing a SQL statement so the rewriter can
  * qualify bare table references (`customer` -> `acme_tpch.tpch1.customer`).
  */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema: Option[String]
)

object ColumnPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  final case class Denied(reason: String) extends Outcome
  case object Passthrough                 extends Outcome

  /** Inner rewriter could not parse the SQL. Routed identically to [[Passthrough]] (the original
    * SQL is forwarded to the node) but tagged separately on the `column_policy_rewrites_total`
    * counter so dashboards can distinguish "no policy applied" from "rewriter blind".
    */
  case object PassthroughParseFailed extends Outcome

  /** Inner rewriter raised a deny because the table/schema/catalog could not be resolved, not
    * because a policy matched. Same wire-level error as [[Denied]] but tagged separately so
    * dashboards can split policy denies from missing-coordinate denies.
    */
  case object DeniedUnresolvedTable extends Outcome

  /** Heuristic for spotting a deny that originated from jsqltranspiler's
    * Table/Schema/Catalog/ColumnNotFound* exceptions: their messages contain "not found", "not
    * declared", or "unknown". Anything else falls through to a regular policy-driven [[Denied]].
    */
  private[cls] def looksUnresolvedTable(reason: String): Boolean =
    val r = reason.toLowerCase
    r.contains("not found") || r.contains("not declared") || r.contains("unknown")

/** Thin facade around a [[SchemaAwareSqlRewriter]]. Handles the IO surface (catalog lookups for the
  * FROM-item tables) plus the early-exit conditions (feature disabled, superuser, non-SELECT, no
  * policies) and delegates the actual SQL walk to the inner rewriter.
  *
  * `enabled` is the kill switch, on by default. When false, every call short-circuits to
  * [[Passthrough]] without touching the catalog or the inner rewriter. Operators opt out via
  * `quack-on-demand.cls.enabled = false` (or `QOD_CLS_ENABLED=false`).
  */
final class ColumnPolicyRewriter(
    catalog: ColumnCatalog,
    inner: SchemaAwareSqlRewriter = new JsqltranspilerRewriter,
    unresolvedMode: UnresolvedMode = UnresolvedMode.Pass,
    enabled: Boolean = true
):
  import ColumnPolicyRewriter._

  def rewrite(
      sql: String,
      kind: StatementKind,
      eff: EffectiveSet,
      ctx: SchemaContext
  ): IO[Outcome] =
    if !enabled then IO.pure(Passthrough)
    else if eff.user.tenant.isEmpty then IO.pure(Passthrough)
    else if kind != StatementKind.Select then IO.pure(Passthrough)
    else if eff.columnPolicies.isEmpty then IO.pure(Passthrough)
    else
      buildSchema(sql, ctx).map { schema =>
        inner.rewrite(
          sql = sql,
          schema = schema,
          policies = eff.columnPolicies,
          defaultCatalog = ctx.defaultDatabase,
          defaultSchema = ctx.defaultSchema,
          unresolvedMode = unresolvedMode
        ) match
          case RewriteOutcome.Rewritten(s)   => Rewritten(s)
          case RewriteOutcome.Denied(reason) =>
            if looksUnresolvedTable(reason) then DeniedUnresolvedTable else Denied(reason)
          case RewriteOutcome.Passthrough => Passthrough
          case RewriteOutcome.ParseFailed => PassthroughParseFailed
      }

  /** Pre-parse to enumerate FROM-item tables and fetch their column lists from the catalog.
    * Failures (unparseable SQL, missing catalog entry) silently omit the table - the resolver's
    * `unresolvedMode` then decides what to do.
    *
    * System-schema tables (information_schema, pg_catalog) are skipped: the tenant catalog only
    * knows user tables (it returns Nil for them, which used to trip the STRICT resolver into a
    * fail-closed deny of harmless metadata queries), and this map's bare-table-name keys land under
    * the session's CURRENT_SCHEMA anyway, so they could never match a schema-qualified
    * `information_schema.x` reference. Instead the inner [[JsqltranspilerRewriter]] seeds the
    * resolver with DuckDB's fixed system-catalog shapes from [[SystemSchemaColumns]] under their
    * real schema names. A system table not in that static set stays unresolved and keeps failing
    * closed (worst case: a denied metadata query, never a leak).
    */
  private def buildSchema(sql: String, ctx: SchemaContext): IO[Map[String, List[String]]] =
    Try(CCJSqlParserUtil.parse(sql)) match
      case Failure(_)            => IO.pure(Map.empty)
      case Success(stmt: Select) =>
        val tables = collectTables(stmt, ctx)
        tables.toList
          .traverse { case (key, (cat, sch, tab)) =>
            if SystemSchemaColumns.isSystemSchema(sch) then
              IO.pure(None) // resolved via SystemSchemaColumns inside the inner rewriter
            else catalog.columnsOf(cat, sch, tab).map(cols => Some(key -> cols))
          }
          .map(_.flatten.toMap)
      case Success(_) => IO.pure(Map.empty)

  /** Every physical table referenced ANYWHERE in the statement, keyed by its raw (bare) name.
    * [[net.sf.jsqlparser.util.TablesNamesFinder]] walks FROM items, joins, CTE bodies, set-op arms
    * AND expression subqueries (EXISTS / IN / ANY / scalar) while excluding CTE names - the
    * hand-rolled FROM-walker this replaces missed expression-nested subqueries, which left their
    * tables out of the schema map entirely (the root of the subquery fail-open gap: a table the map
    * never mentions can neither resolve nor deny).
    */
  private def collectTables(
      stmt: Select,
      ctx: SchemaContext
  ): Map[String, (String, String, String)] =
    val names =
      Try {
        val finder = new net.sf.jsqlparser.util.TablesNamesFinder()
        finder.getTableList(stmt: net.sf.jsqlparser.statement.Statement).asScala.toList
      }.getOrElse(Nil)
    def unquote(s: String) = s.stripPrefix("\"").stripSuffix("\"")
    names.flatMap { raw =>
      raw.split('.').toList.map(unquote) match
        case tab :: Nil =>
          Some(tab -> (ctx.defaultDatabase.getOrElse(""), ctx.defaultSchema.getOrElse(""), tab))
        case sch :: tab :: Nil =>
          Some(tab -> (ctx.defaultDatabase.getOrElse(""), sch, tab))
        case cat :: sch :: tab :: Nil =>
          Some(tab -> (cat, sch, tab))
        case _ => None
    }.toMap
