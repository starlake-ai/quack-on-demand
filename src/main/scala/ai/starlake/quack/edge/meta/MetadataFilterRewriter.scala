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
import net.sf.jsqlparser.statement.show.ShowTablesStatement
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
  * Fail-closed, and DENIAL is how it stays that way: a filterable reference the substitution walk
  * cannot reach is refused, not filtered. The walk alone cannot promise this (SQL puts tables in
  * more places than it visits), so two independent counts of the statement's filterable references
  * are taken up front and both must be covered by the substitutions:
  *
  *   - AST occurrences, tallied on jsqlparser's own traversal by node identity. Exact where the
  *     traversal reaches, blind where it does not (ORDER BY, GROUP BY and window PARTITION BY
  *     subqueries are NOT visited - the same blind spots as the walk, which is why one count is not
  *     enough).
  *   - A textual count of the raw statement with string literals and comments stripped. Coarser,
  *     but it does not depend on knowing where the traversal stops, so it catches the positions
  *     nothing else sees.
  *
  * A count that cannot be taken is also a denial. Two consequences are accepted deliberately: a
  * reference in an unsupported position is denied rather than filtered, and a CROSS-CATALOG
  * filterable reference sitting in a traversal blind spot is denied even though the walk leaves
  * cross-catalog metadata alone on purpose (rare, fail-closed, and the validator would have
  * grant-gated that reference anyway).
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
                // information_schema references the count would otherwise include too.
                filterableOccurrences(sel, ctx) match
                  case None =>
                    // The reference count is what makes the fail-closed promise true, so
                    // losing it denies rather than admits: a statement the counter chokes
                    // on is exactly the shape the substitution walk is likely blind to.
                    Denied(
                      "cannot analyse the information_schema references in this statement"
                    )
                  case Some(refs) =>
                    val walker = new Walker(grants, ctx)
                    walker.walkSelect(sel)
                    // Cross-catalog references are left alone on purpose (they stay
                    // grant-gated by the validator), so they are accounted for rather
                    // than counted as unfiltered leftovers.
                    val accounted = walker.substitutions + refs.crossCatalog
                    walker.failure match
                      case Some(reason) => Denied(reason)
                      case None         =>
                        if refs.nodes > walker.substitutions ||
                          textualRefCount(sql) > accounted
                        then
                          Denied(
                            "information_schema reference in a position this filter cannot " +
                              "rewrite; query it directly in the FROM clause instead"
                          )
                        else if walker.substitutions > 0 then Rewritten(sel.toString)
                        else Passthrough
              case Some(st: ShowTablesStatement) =>
                // A SHOW TABLES the textual fast path did not recognise (leading comment,
                // odd whitespace) but jsqlparser did. Same treatment, so the form cannot
                // dodge the filter by being spelled differently.
                if ShowTables.isPlain(st) then ShowTables.filteredListing(grants, ctx)
                else ShowTables.denial
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

  /** How many filterable references the statement carries, counted as table OCCURRENCES on
    * jsqlparser's own traversal, which reaches FROM items, joins, CTE bodies, set-op arms, WHERE /
    * HAVING and the expression positions the substitution walk does not enter (function arguments,
    * CASE arms). It does NOT reach ORDER BY, GROUP BY or window PARTITION BY subqueries: those are
    * the textual tripwire's job ([[textualRefCount]]). Compared against the walker's substitution
    * count, so a reference where the walker cannot rewrite it denies the statement instead of
    * riding through unfiltered.
    *
    * Occurrences rather than [[net.sf.jsqlparser.util.TablesNamesFinder]]'s de-duplicated NAME list
    * is the load-bearing choice: with names, one substituted FROM item covers for every other
    * reference to the same table, and `SELECT coalesce((SELECT ... FROM information_schema.tables),
    * 'x') FROM information_schema.tables` walks straight out with its subquery unfiltered.
    *
    * None when the traversal fails: the caller denies, because a statement that breaks the counter
    * is precisely the shape the walk is most likely blind to.
    */
  private def filterableOccurrences(sel: Select, ctx: SchemaContext): Option[RefCounts] =
    val counter = new FilterableOccurrences(ctx.defaultDatabase.getOrElse(""))
    Try {
      counter.getTableList(sel: Statement)
      RefCounts(counter.count, counter.crossCatalogCount)
    }.toOption

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
    * [[filterableOccurrences]] cross-check rather than by this walk.
    */
  private final class Walker(grants: List[RolePermission], ctx: SchemaContext):
    var failure: Option[String] = None

    /** Filterable references this walker replaced, checked against [[filterableOccurrences]]. */
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
      filterableMeta(t, ctx.defaultDatabase.getOrElse(""))

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

  /** Some(tableName) when `t` names a filterable information_schema table of the session catalog.
    * The single rule both the substitution walk and [[FilterableOccurrences]] apply: they must
    * agree exactly, or the cross-check either denies valid statements or misses a leak.
    */
  private def filterableMeta(t: Table, sessionCat: String): Option[String] =
    val schema = Option(t.getSchemaName).getOrElse("")
    val cat    = Option(t.getDatabase).flatMap(d => Option(d.getDatabaseName)).getOrElse("")
    val name   = Option(t.getName).getOrElse("")
    val catOk  = cat.isEmpty || cat.equalsIgnoreCase(sessionCat)
    if catOk && schema.equalsIgnoreCase(InformationSchema) &&
      FilterableTables.contains(name.toLowerCase)
    then Some(name.toLowerCase)
    else None

  /** Counts filterable table OCCURRENCES by riding jsqlparser's own complete traversal and tallying
    * each visited [[net.sf.jsqlparser.schema.Table]] node, instead of reading the finder's
    * de-duplicated name list.
    */
  private final class FilterableOccurrences(sessionCat: String)
      extends TablesNamesFinder[java.lang.Void]:

    // Keyed on node IDENTITY, not on the table name: two references to the same table are two
    // occurrences (that is the whole point), while one node the traversal happens to visit twice
    // - a CTE body, which it reaches both through the WITH list and through the query - stays one.
    private def identitySet =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Table, java.lang.Boolean])

    private val seen      = identitySet
    private val crossSeen = identitySet

    def count: Int             = seen.size
    def crossCatalogCount: Int = crossSeen.size

    override def visit[S](table: Table, context: S): java.lang.Void =
      if filterableMeta(table, sessionCat).isDefined then seen.add(table)
      else if crossCatalogFilterable(table, sessionCat) then crossSeen.add(table)
      super.visit(table, context)

  /** A filterable information_schema table of some OTHER catalog. The substitution walk leaves
    * these alone by design (cross-catalog metadata stays grant-gated by the validator rather than
    * filtered), so the textual tripwire must not read them as unfiltered leftovers.
    */
  private def crossCatalogFilterable(t: Table, sessionCat: String): Boolean =
    val schema = Option(t.getSchemaName).getOrElse("")
    val cat    = Option(t.getDatabase).flatMap(d => Option(d.getDatabaseName)).getOrElse("")
    val name   = Option(t.getName).getOrElse("")
    cat.nonEmpty && !cat.equalsIgnoreCase(sessionCat) &&
    schema.equalsIgnoreCase(InformationSchema) && FilterableTables.contains(name.toLowerCase)

  private final case class RefCounts(nodes: Int, crossCatalog: Int)

  /** Textual tripwire: how many times the raw statement spells a filterable reference, once string
    * literals and comments are removed so a statement merely mentioning the phrase is not denied.
    * Qualification is not part of the match, since a session-catalog-qualified reference is
    * substituted and a cross-catalog one is accounted for separately.
    *
    * This exists because the AST-side count can only see what jsqlparser's traversal visits, and
    * that traversal skips ORDER BY, GROUP BY and window PARTITION BY subqueries - exactly where the
    * substitution walk is blind too, so the two agreed with each other and both missed the
    * reference. Text does not depend on knowing where the traversal stops.
    */
  private def textualRefCount(sql: String): Int =
    TextualRefRe.findAllMatchIn(stripLiteralsAndComments(sql)).size

  private val TextualRefRe =
    """(?i)"?information_schema"?\s*\.\s*"?(?:schemata|tables|columns|views)"?\b""".r

  private enum ScanState:
    case Sql, Literal, LineComment, BlockComment

  /** Drop single-quoted literals (`''` is an escaped quote, not a terminator), `--` line comments
    * and slash-star block comments, so the tripwire counts references the engine would actually
    * resolve. Double-quoted identifiers are KEPT: those are references.
    *
    * Dollar-quoted strings are not recognised, so a `$$...$$` literal naming a filterable table
    * denies the statement. That is the fail-closed direction and DuckDB clients rarely emit it.
    */
  private[meta] def stripLiteralsAndComments(sql: String): String =
    import ScanState._
    val out   = new StringBuilder(sql.length)
    var i     = 0
    var state = Sql
    while i < sql.length do
      val c    = sql.charAt(i)
      val next = if i + 1 < sql.length then sql.charAt(i + 1) else ' '
      state match
        case Sql =>
          if c == '\'' then
            state = Literal
            i += 1
          else if c == '-' && next == '-' then
            state = LineComment
            i += 2
          else if c == '/' && next == '*' then
            state = BlockComment
            i += 2
          else
            out.append(c)
            i += 1
        case Literal =>
          if c == '\'' && next == '\'' then i += 2
          else if c == '\'' then
            state = Sql
            i += 1
          else i += 1
        case LineComment =>
          if c == '\n' then
            state = Sql
            out.append(c)
            i += 1
          else i += 1
        case BlockComment =>
          if c == '*' && next == '/' then
            state = Sql
            i += 2
          else i += 1
    out.toString

  /** Disjunction: system rows always, plus one clause per grant. TRUE-clause grants short-circuit
    * the whole predicate to keep the SQL readable.
    */
  private[meta] def predicateFor(meta: String, grants: List[RolePermission]): String =
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

  /** SHOW TABLES handling (jsqlparser models it as a statement the filter walker never sees). Plain
    * SHOW TABLES is replaced by the filtered listing matching DuckDB's native single-column `name`
    * output; any other SHOW ... TABLES form is DENIED fail-closed, since it would otherwise ride
    * the ControlFlow admit unfiltered. SHOW ALL TABLES never reaches a filtered principal
    * (UnsupportedStatement -> ParseError -> denied by the validator upstream); the catch-all deny
    * here is defense in depth only.
    *
    * [[replace]] is the textual fast path, matched BEFORE parsing, and returns None for anything
    * that is not a SHOW ... TABLES form. Forms it does not recognise (a leading comment, odd
    * whitespace) still parse to a `ShowTablesStatement`, which `rewrite` routes back here through
    * [[isPlain]] / [[filteredListing]] / [[denial]] - the two paths must stay in agreement or the
    * unrecognised spelling becomes an unfiltered bypass.
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
        case ShowTablesPlainRe() => Some(filteredListing(grants, ctx))
        case ShowTablesAnyRe()   => Some(denial)
        case _                   => None

    /** True for a bare `SHOW TABLES`: no source database, no LIKE / WHERE selector, no modifier
      * (EXTENDED / FULL) and no selection mode. Everything else names or narrows a target this
      * replacement cannot reproduce, so it is denied.
      */
    def isPlain(st: ShowTablesStatement): Boolean =
      st.getDbName == null && st.getLikeExpression == null && st.getWhereCondition == null &&
        st.getSelectionMode == null &&
        Option(st.getModifiers).forall(_.isEmpty)

    /** The filtered stand-in for a plain SHOW TABLES: DuckDB's native single-column `name` shape,
      * scoped to the session schema.
      */
    def filteredListing(grants: List[RolePermission], ctx: SchemaContext): MetadataFilterOutcome =
      val pred   = predicateFor("tables", grants)
      val schema = lit(ctx.defaultSchema.getOrElse("main"))
      MetadataFilterOutcome.Rewritten(
        s"SELECT table_name AS name FROM information_schema.tables " +
          s"WHERE table_schema = $schema AND ($pred) ORDER BY name"
      )

    def denial: MetadataFilterOutcome =
      MetadataFilterOutcome.Denied(
        "SHOW TABLES variants are not supported with filtered metadata; " +
          "query information_schema.tables instead"
      )
