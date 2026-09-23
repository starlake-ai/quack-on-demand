package ai.starlake.quack.edge.cls

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import cats.effect.IO
import cats.syntax.traverse._
import ai.starlake.acl.parser.TableExtractor
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
    val r = reason.toLowerCase(Locale.ROOT)
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
    else if readsOnlyCatalogFunctions(sql) then IO.pure(Passthrough)
    else
      buildSchema(sql, ctx).map { schema =>
        PositionalReferences.resolve(sql, schema) match
          case Left(reason) => Denied(reason)
          case Right(named) =>
            inner.rewrite(
              sql = named,
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

  /** True for a single statement whose only FROM items are DuckDB's filterable catalog functions
    * (`duckdb_tables()`, `duckdb_views()`, `duckdb_schemas()`, `duckdb_columns()`) and which reads
    * no physical table anywhere: the quack client's attach-time catalog sync (`... FROM
    * duckdb_tables() UNION ALL ... FROM duckdb_views()`, issue #114). Nothing in it can carry a
    * policy-bearing column, while the column resolver cannot model a table function at all and
    * reports ParseFailed, which the router denies fail-closed: before this, every principal holding
    * ANY column policy could not ATTACH.
    *
    * Decided on the ACL parser's walk, which is complete by construction (ORDER BY, GROUP BY and
    * window subqueries included, unlike jsqlparser's own traversal), so a policy-bearing table
    * hidden anywhere still reaches the resolver. Exactly one statement: `parse` truncates a batch
    * to its first statement, and a batch is refused by the resolver today; a shortcut on its head
    * would forward the tail unmasked. Any other table function (`read_parquet`, ...) keeps the
    * fail-closed path, as does a catalog function beside a physical table.
    */
  private def readsOnlyCatalogFunctions(sql: String): Boolean =
    Try(CCJSqlParserUtil.parseStatements(sql)).toOption
      .map(_.getStatements)
      .filter(_.size == 1)
      .flatMap(sts => Option(sts.get(0)).collect { case sel: Select => sel })
      .exists { sel =>
        val extraction = TableExtractor.extract(sel)
        extraction.tables.isEmpty && extraction.unsupported.nonEmpty &&
        extraction.unsupported.forall(u =>
          TableExtractor.tableFunctionName(u).flatMap(TableExtractor.catalogFunctionCall).isDefined
        )
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

/** DuckDB positional column references (`#n`, 1-based, FROM-relative) resolved to the column names
  * the policy matcher needs. The quack client pushes every scan down as `SELECT #1, #2 FROM
  * <table>`; jsqlparser parses `#1` as a Column literally named `#1`, the resolver finds no such
  * column and, in the default lenient mode, forwards the scan UNMASKED: every column policy holder
  * could read the masked values through ATTACH (issue #114, second report).
  *
  * Only the shape whose numbering is unambiguous is resolved: one SELECT, no WITH, no set
  * operation, FROM exactly one physical table with no join, whose column list the catalog knows.
  * There `#n` means the table's n-th column in the projection, in WHERE / HAVING / GROUP BY and
  * inside expressions alike. Everything else is refused fail-closed rather than guessed: joins and
  * derived tables number a combined list, ORDER BY numbers the PROJECTION instead, and a leftover
  * `#n` after the walk (an ORDER BY term, a position the walk did not reach) trips the textual
  * count. A statement without any `#n` is returned untouched, so nothing else is reshaped.
  */
private[cls] object PositionalReferences:

  private val PositionalRe = """^#(\d+)$""".r

  /** `#n` outside single-quoted literals and quoted identifiers (`'#1'` is text, `"#1"` a name). */
  private val TextualRe = """(?<!["\w])#\d+""".r
  private val LiteralRe = """'(?:[^']|'')*'""".r

  private def textualCount(sql: String): Int =
    TextualRe.findAllMatchIn(LiteralRe.replaceAllIn(sql, "''")).size

  private val UnsupportedShape =
    "positional column references (#n) are supported only in a single-table SELECT " +
      "(no join, subquery or set operation) and not as an ORDER BY, GROUP BY or HAVING term; " +
      "name the columns instead"

  /** The clauses where DuckDB does NOT number the FROM columns: ORDER BY numbers the projection,
    * GROUP BY / HAVING bind `#n` to neither. A `#n` there is refused, never renamed.
    */
  private def hasPositionalInProjectionRelativeClause(ps: PlainSelect): Boolean =
    val orderBy = Option(ps.getOrderByElements).map(_.asScala.map(_.toString).mkString(" "))
    val groupBy = Option(ps.getGroupBy).map(_.toString)
    val having  = Option(ps.getHaving).map(_.toString)
    List(orderBy, groupBy, having).flatten.exists(textualCount(_) > 0)

  def resolve(sql: String, schema: Map[String, List[String]]): Either[String, String] =
    if textualCount(sql) == 0 then Right(sql)
    else
      Try(CCJSqlParserUtil.parseStatements(sql)).toOption
        .map(_.getStatements.asScala.toList) match
        case Some(List(ps: PlainSelect))
            if ps.getWithItemsList == null || ps.getWithItemsList.isEmpty =>
          val single = Option(ps.getJoins).forall(_.isEmpty)
          Option(ps.getFromItem) match
            case Some(t: net.sf.jsqlparser.schema.Table) if single =>
              val cols = schema.getOrElse(t.getName, Nil)
              if hasPositionalInProjectionRelativeClause(ps) then Left(UnsupportedShape)
              else if cols.isEmpty then
                Left(s"positional column references (#n) on ${t.getName}: table columns unknown")
              else
                val walk = new Numbering(cols)
                Try(walk.getTableList(ps: net.sf.jsqlparser.statement.Statement)).toOption match
                  case None    => Left(UnsupportedShape)
                  case Some(_) =>
                    walk.failure match
                      case Some(reason) => Left(reason)
                      case None         =>
                        val named = ps.toString
                        if textualCount(named) > 0 then Left(UnsupportedShape) else Right(named)
            case _ => Left(UnsupportedShape)
        case _ => Left(UnsupportedShape)

  /** Rides jsqlparser's own traversal and renames every `#n` Column in place. */
  private final class Numbering(cols: List[String])
      extends net.sf.jsqlparser.util.TablesNamesFinder[java.lang.Void]:
    var failure: Option[String] = None

    override def visit[S](column: net.sf.jsqlparser.schema.Column, context: S): java.lang.Void =
      Option(column.getColumnName).foreach {
        case PositionalRe(digits) =>
          val n = Try(digits.toInt).getOrElse(0)
          if n < 1 || n > cols.size then
            failure =
              Some(s"positional column reference #$digits out of range (${cols.size} columns)")
          else column.setColumnName(quoteIfNeeded(cols(n - 1)))
        case _ => ()
      }
      super.visit(column, context)

    private def quoteIfNeeded(name: String): String =
      if name.matches("[a-z_][a-z0-9_]*") then name
      else "\"" + name.replace("\"", "\"\"") + "\""
