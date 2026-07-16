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
    */
  private def buildSchema(sql: String, ctx: SchemaContext): IO[Map[String, List[String]]] =
    Try(CCJSqlParserUtil.parse(sql)) match
      case Failure(_)            => IO.pure(Map.empty)
      case Success(stmt: Select) =>
        val tables = schemaKeys(collectTables(stmt, ctx))
        tables.toList
          .traverse { case (key, (cat, sch, tab)) =>
            catalog.columnsOf(cat, sch, tab).map(cols => key -> cols)
          }
          .map(_.toMap)
      case Success(_) => IO.pure(Map.empty)

  private def collectTables(
      stmt: Select,
      ctx: SchemaContext
  ): Map[String, (String, String, String)] =
    val acc = scala.collection.mutable.Map.empty[String, (String, String, String)]
    def visitSel(sel: Select): Unit = sel match
      case ps: PlainSelect =>
        Option(ps.getFromItem).foreach {
          case t: net.sf.jsqlparser.schema.Table => indexTable(t, ctx, acc)
          case sub: ParenthesedSelect            => visitSel(sub.getSelect)
          case _                                 => ()
        }
        Option(ps.getJoins).foreach(_.asScala.foreach { j =>
          Option(j.getRightItem).foreach {
            case t: net.sf.jsqlparser.schema.Table => indexTable(t, ctx, acc)
            case sub: ParenthesedSelect            => visitSel(sub.getSelect)
            case _                                 => ()
          }
        })
      case ps: ParenthesedSelect => visitSel(ps.getSelect)
      case sol: SetOperationList =>
        Option(sol.getSelects).foreach(_.asScala.foreach(visitSel))
      case _ => ()
    Option(stmt.getWithItemsList).foreach(_.asScala.foreach { wi =>
      Option(wi.getParenthesedStatement).foreach {
        case ps: ParenthesedSelect => visitSel(ps.getSelect)
        case _                     => ()
      }
    })
    visitSel(stmt)
    acc.toMap

  /** Key the schema map by the raw table name AND by each alias pointing at it, so the resolver
    * accepts both `SELECT * FROM customer` and `SELECT c.* FROM customer c`. JSQLColumResolver uses
    * the row's first element as the table-name match key.
    */
  private def schemaKeys(
      tables: Map[String, (String, String, String)]
  ): Map[String, (String, String, String)] =
    // We seed the map with raw table names so the resolver's expansion of `c.*` finds
    // the column list under the base table name. Alias-keyed entries are dropped because
    // they would duplicate the same column list under a key that is just an alias, and the
    // resolver only matches by table identifier.
    tables.values.map { case (cat, sch, tab) => tab -> (cat, sch, tab) }.toMap

  private def indexTable(
      t: net.sf.jsqlparser.schema.Table,
      ctx: SchemaContext,
      acc: scala.collection.mutable.Map[String, (String, String, String)]
  ): Unit =
    val rawName    = t.getName
    val schemaName = Option(t.getSchemaName).getOrElse(ctx.defaultSchema.getOrElse(""))
    val catalog    = Option(t.getDatabase)
      .flatMap(d => Option(d.getDatabaseName))
      .getOrElse(ctx.defaultDatabase.getOrElse(""))
    val key = Option(t.getAlias).map(_.getName).getOrElse(rawName)
    acc(key) = (catalog, schemaName, rawName)
