package ai.starlake.quack.edge.meta

import ai.starlake.quack.edge.cls.SchemaContext
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RolePermission
import net.sf.jsqlparser.expression.operators.relational.{ExistsExpression, InExpression}
import net.sf.jsqlparser.expression.{
  Alias,
  AnyComparisonExpression,
  BinaryExpression,
  Expression,
  NotExpression,
  Parenthesis
}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.util.TablesNamesFinder
import net.sf.jsqlparser.statement.select.{
  FromItem,
  ParenthesedSelect,
  PlainSelect,
  Select,
  SetOperationList
}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Result of the metadata filter for one statement. */
enum MetadataFilterOutcome:
  /** Nothing to filter (or the principal is exempt): forward the original SQL. */
  case Passthrough

  /** Forward `sql` instead of the caller's statement. */
  case Rewritten(sql: String)

  /** Fail-closed refusal: a filterable reference could not be substituted. */
  case Denied(reason: String)

/** Filters system-catalog reads to the principal's granted objects (spec
  * 2026-08-20-filtered-metadata-design): each `information_schema.{schemata,tables,columns,views}`
  * reference of the SESSION catalog is replaced by a derived table whose WHERE keeps the system
  * rows plus the rows covered by a Read-covering grant. Runs for every non-superuser principal
  * without an explicit information_schema grant; the ACL validator implicitly admits exactly the
  * references this class filters (same flag).
  *
  * Fail-closed: a filterable reference this walker cannot substitute denies the statement; it never
  * passes one through unfiltered.
  */
final class MetadataFilterRewriter(enabled: Boolean = true):

  import MetadataFilterOutcome._
  import MetadataFilterRewriter._

  def rewrite(sql: String, eff: EffectiveSet, ctx: SchemaContext): MetadataFilterOutcome =
    if !enabled then Passthrough
    else if eff.user.tenant.isEmpty then Passthrough // superuser: unfiltered, as today
    else if hasWildcardAll(eff) then Passthrough
    else
      val grants = readGrants(eff, ctx)
      if grants.exists(_.schemaName.equalsIgnoreCase(InformationSchema)) then
        // Operator escape hatch: an EXPLICIT (schema named literally) information_schema
        // grant keeps today's unfiltered read.
        Passthrough
      else
        ShowTables.replace(sql, grants, ctx) match
          case Some(outcome) => outcome
          case None          =>
            Try(CCJSqlParserUtil.parse(sql)).toOption match
              case Some(sel: Select) =>
                // Counted BEFORE the walk: the substitutions themselves introduce inner
                // information_schema references the finder would otherwise count too.
                val refs   = filterableRefCount(sel, ctx)
                val walker = new Walker(grants, ctx)
                walker.walkSelect(sel)
                walker.failure match
                  case Some(reason) => Denied(reason)
                  case None         =>
                    if refs > walker.substitutions then
                      Denied(
                        "cannot filter every information_schema reference in this statement; " +
                          "select from information_schema directly instead"
                      )
                    else if walker.substitutions > 0 then Rewritten(sel.toString)
                    else Passthrough
              case _ =>
                // Non-SELECT or unparseable: nothing filterable that the ACL layer has not
                // already gated (DESCRIBE / SHOW-table forms are Read accesses now;
                // unparseable statements are denied upstream unless wildcard-ALL).
                Passthrough

  /** Session-catalog Read-covering grants: verb RO/RW/ALL and catalog wildcard-or-equal to the
    * session database.
    */
  private def readGrants(eff: EffectiveSet, ctx: SchemaContext): List[RolePermission] =
    val sessionCat = ctx.defaultDatabase.getOrElse("")
    eff.permissions.filter { p =>
      ReadCoveringVerbs.contains(p.verb.toUpperCase) &&
      (p.catalogName == RolePermission.Wildcard || p.catalogName.equalsIgnoreCase(sessionCat))
    }

  /** How many filterable references the statement carries, as jsqlparser's own table-name finder
    * sees them (it descends FROM items, joins, CTE bodies, set-op arms AND expression subqueries,
    * including the function-argument positions the substitution walker does not enter). Compared
    * against the walker's substitution count, so a reference sitting in a position the walker
    * cannot rewrite denies the statement instead of riding through unfiltered. The finder may
    * de-duplicate repeated names, hence the one-sided `refs > substitutions` test: it can only ever
    * under-count, never manufacture a false denial.
    */
  private def filterableRefCount(sel: Select, ctx: SchemaContext): Int =
    val names = Try(new TablesNamesFinder().getTableList(sel: Statement).asScala.toList)
      .getOrElse(Nil)
    def unquote(s: String) = s.stripPrefix("\"").stripSuffix("\"")
    val sessionCat         = ctx.defaultDatabase.getOrElse("")
    names.count { raw =>
      raw.split('.').toList.map(unquote) match
        case sch :: tab :: Nil =>
          sch.equalsIgnoreCase(InformationSchema) && FilterableTables.contains(tab.toLowerCase)
        case cat :: sch :: tab :: Nil =>
          cat.equalsIgnoreCase(sessionCat) && sch.equalsIgnoreCase(InformationSchema) &&
          FilterableTables.contains(tab.toLowerCase)
        case _ => false
    }

  private def hasWildcardAll(eff: EffectiveSet): Boolean =
    eff.permissions.exists(p =>
      p.verb.equalsIgnoreCase("ALL") &&
        p.catalogName == RolePermission.Wildcard &&
        p.schemaName == RolePermission.Wildcard &&
        p.tableName == RolePermission.Wildcard
    )

  /** FromItem walker. Mirrors the RLS traversal surface: PlainSelect FROM + JOIN items,
    * WHERE/HAVING/projection expression subqueries, set-op arms, CTE bodies. Substitution happens
    * on FromItems only (tables appear nowhere else). Anything outside that surface is caught by the
    * [[filterableRefCount]] cross-check rather than by this walk.
    */
  private final class Walker(grants: List[RolePermission], ctx: SchemaContext):
    var failure: Option[String] = None

    /** Filterable references this walker replaced, checked against [[filterableRefCount]]. */
    var substitutions: Int = 0

    def walkSelect(sel: Select): Unit =
      // CTE bodies first, so a filterable table inside a WITH item is substituted too.
      Option(sel.getWithItemsList).foreach(_.asScala.foreach { wi =>
        Option(wi.getParenthesedStatement).foreach {
          case ps: ParenthesedSelect => walkSelect(ps.getSelect)
          case _                     => ()
        }
      })
      sel match
        case ps: PlainSelect       => walkPlain(ps)
        case w: ParenthesedSelect  => walkSelect(w.getSelect)
        case sol: SetOperationList =>
          Option(sol.getSelects).foreach(_.asScala.foreach(walkSelect))
        case _ => ()

    private def walkPlain(ps: PlainSelect): Unit =
      Option(ps.getFromItem).foreach(fi => substituteIn(fi)(ps.setFromItem))
      Option(ps.getJoins).foreach(_.asScala.foreach { j =>
        Option(j.getFromItem).foreach(fi => substituteIn(fi)(j.setFromItem))
      })
      // Expression subqueries: descend the clauses that can hold a nested Select. Only Select
      // nodes matter, so a light hand-rolled descent beats a full visitor here.
      def visitExpr(e: Expression): Unit = e match
        case null                 => ()
        case s: ParenthesedSelect => walkSelect(s.getSelect)
        case s: Select            => walkSelect(s)
        case n: NotExpression     => visitExpr(n.getExpression)
        case p: Parenthesis       => visitExpr(p.getExpression)
        case ex: ExistsExpression => visitExpr(ex.getRightExpression)
        case ix: InExpression     =>
          visitExpr(ix.getLeftExpression); visitExpr(ix.getRightExpression)
        case ac: AnyComparisonExpression => Option(ac.getSelect).foreach(walkSelect)
        case b: BinaryExpression         =>
          visitExpr(b.getLeftExpression); visitExpr(b.getRightExpression)
        case _ => ()
      Option(ps.getWhere).foreach(visitExpr)
      Option(ps.getHaving).foreach(visitExpr)
      Option(ps.getSelectItems).foreach(_.asScala.foreach(si => visitExpr(si.getExpression)))

    /** Substitute one FROM/JOIN item in place through `install`, or descend into it. */
    private def substituteIn(item: FromItem)(install: FromItem => Unit): Unit = item match
      case t: Table =>
        filterableName(t).foreach { meta =>
          replacementFor(t, meta) match
            case Some(rep) => install(rep); substitutions += 1
            case None      =>
              failure = Some(s"cannot filter reference to information_schema.$meta")
        }
      case sub: ParenthesedSelect => walkSelect(sub.getSelect)
      case _                      => ()

    /** Some(tableName) when `t` is a filterable session-catalog information_schema table. */
    private def filterableName(t: Table): Option[String] =
      val schema     = Option(t.getSchemaName).getOrElse("")
      val cat        = Option(t.getDatabase).flatMap(d => Option(d.getDatabaseName)).getOrElse("")
      val name       = Option(t.getName).getOrElse("")
      val sessionCat = ctx.defaultDatabase.getOrElse("")
      val catOk      = cat.isEmpty || cat.equalsIgnoreCase(sessionCat)
      if catOk && schema.equalsIgnoreCase(InformationSchema) &&
        FilterableTables.contains(name.toLowerCase)
      then Some(name.toLowerCase)
      else None

    /** The derived table replacing `t`, carrying t's alias (or its name when it had none) so outer
      * `alias.col` references still resolve. Built by parsing a one-off `SELECT * FROM (...) x`
      * rather than assembling the node tree by hand.
      */
    private def replacementFor(t: Table, meta: String): Option[FromItem] =
      val derived =
        s"SELECT * FROM (SELECT * FROM information_schema.$meta " +
          s"WHERE ${predicateFor(meta, grants)}) qod_meta"
      Try(CCJSqlParserUtil.parse(derived)).toOption
        .collect { case ps: PlainSelect => ps.getFromItem }
        .map { fromItem =>
          fromItem.setAlias(Option(t.getAlias).getOrElse(new Alias(t.getName, false)))
          fromItem
        }

object MetadataFilterRewriter:

  val FilterableTables: Set[String] = Set("schemata", "tables", "columns", "views")

  private val InformationSchema   = "information_schema"
  private val ReadCoveringVerbs   = Set("RO", "RW", "ALL")
  private val SystemRowClauseTab  = "table_schema IN ('information_schema', 'pg_catalog')"
  private val SystemRowClauseSchm = "schema_name IN ('information_schema', 'pg_catalog')"

  private def lit(s: String): String = "'" + s.replace("'", "''") + "'"

  /** Disjunction: system rows always, plus one clause per grant. TRUE-clause grants short-circuit
    * the whole predicate to keep the SQL readable.
    */
  def predicateFor(meta: String, grants: List[RolePermission]): String =
    val w = RolePermission.Wildcard
    if meta == "schemata" then
      val clauses = grants.map { g =>
        if g.schemaName == w then "TRUE" else s"schema_name = ${lit(g.schemaName)}"
      }
      if clauses.contains("TRUE") then "TRUE"
      else (SystemRowClauseSchm :: clauses).distinct.mkString("(", ") OR (", ")")
    else
      val clauses = grants.map { g =>
        (g.schemaName == w, g.tableName == w) match
          case (true, true)   => "TRUE"
          case (false, true)  => s"table_schema = ${lit(g.schemaName)}"
          case (true, false)  => s"table_name = ${lit(g.tableName)}"
          case (false, false) =>
            s"table_schema = ${lit(g.schemaName)} AND table_name = ${lit(g.tableName)}"
      }
      if clauses.contains("TRUE") then "TRUE"
      else (SystemRowClauseTab :: clauses).distinct.mkString("(", ") OR (", ")")

  /** SHOW TABLES handling, string-matched BEFORE parsing (jsqlparser models it as a statement the
    * filter walker never sees). Plain SHOW TABLES is replaced by the filtered listing matching
    * DuckDB's native single-column `name` output; any other SHOW ... TABLES form is DENIED
    * fail-closed (those parse as ShowTablesStatement and would otherwise ride the ControlFlow admit
    * unfiltered). SHOW ALL TABLES never reaches a filtered principal (UnsupportedStatement ->
    * ParseError -> denied by the validator upstream); the catch-all deny here is defense in depth
    * only. Returns None for anything that is not a SHOW ... TABLES form.
    */
  private[meta] object ShowTables:
    private val ShowTablesPlainRe = """(?is)^\s*SHOW\s+TABLES\s*;?\s*$""".r
    private val ShowTablesAnyRe   = """(?is)^\s*SHOW\s+(?:ALL\s+)?TABLES\b.*""".r

    def replace(
        sql: String,
        grants: List[RolePermission],
        ctx: SchemaContext
    ): Option[MetadataFilterOutcome] =
      sql match
        case ShowTablesPlainRe() =>
          val pred   = predicateFor("tables", grants)
          val schema = lit(ctx.defaultSchema.getOrElse("main"))
          Some(
            MetadataFilterOutcome.Rewritten(
              s"SELECT table_name AS name FROM information_schema.tables " +
                s"WHERE table_schema = $schema AND ($pred) ORDER BY name"
            )
          )
        case ShowTablesAnyRe() =>
          Some(
            MetadataFilterOutcome.Denied(
              "SHOW TABLES variants are not supported with filtered metadata; " +
                "query information_schema.tables instead"
            )
          )
        case _ => None
