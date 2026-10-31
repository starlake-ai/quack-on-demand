package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.{Config, DenyReason, TableRef}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableExtractor, Verb}
import ai.starlake.quack.edge.cls.{
  ColumnCatalog,
  ColumnPolicyRewriter,
  SchemaContext,
  UnresolvedMode
}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.sql.SqlCommentStripper
import cats.effect.unsafe.implicits.global
import net.sf.jsqlparser.parser.{CCJSqlParser, CCJSqlParserUtil}
import net.sf.jsqlparser.parser.feature.{Feature, FeatureConfiguration}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.{Select, Values}

import scala.jdk.CollectionConverters._

enum GuardOutcome:
  case Allow
  case Deny(reason: String)

/** Closes the CLS/RLS write-wrapping bypass (spec 2026-08-20-protected-write-guard). For a
  * non-SELECT statement, or any batch of more than one statement, from a principal with enforced
  * policies, denies when the read side exposes a protected table: any read of an RLS table, or a
  * CLS table whose masked columns actually appear in the read. Any read it cannot isolate denies
  * fail-closed. Never rewrites; only allows or denies.
  *
  * On EVERY statement kind, a plain SELECT included, it also denies what the rewriters cannot read
  * as DuckDB does (spec decision 27): a quoted name holding a dot ([[DottedIdentifierScreen]]), a
  * call whose target is a string ([[CatalogReachingCalls]]: `query_table('t')`, `query('...')`, a
  * reading table macro such as `histogram('t', c)`), a SQL-valued setting, a batch that moves the
  * schema or search path ([[SessionScopeScreen]]: every bare name after it resolves past the policy
  * keyed on the default schema), and a two-part name headed by an attached catalog
  * (`acme_tpch.customer`), which DuckDB binds catalog-first while the rewriters and this guard's
  * policy match read it schema-first (audit F20, F22). A principal without policies is judged by
  * the node alone.
  *
  * The guard owns its CLS detection oracle: it builds a `ColumnPolicyRewriter` internally in
  * `UnresolvedMode.Deny`, so a table whose columns the catalog cannot enumerate denies rather than
  * passing through unexpanded. Analysis is per statement: a batch is allowed only when every
  * statement is clean, so a benign write cannot launder a protected read in a sibling statement.
  *
  * `clsEnabled` and `rlsEnabled` are required, not defaulted (final review I1): false switches the
  * guard off for that kind of policy, which a construction site must say rather than forget.
  */
final class ProtectedWriteGuard(
    catalog: ColumnCatalog,
    clsEnabled: Boolean,
    rlsEnabled: Boolean
):

  import GuardOutcome._

  // Deny-mode oracle: an unresolvable protected table must deny, not pass through.
  private val oracle =
    new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny, enabled = true)

  /** `clientDataScans`: the statement came through the native Quack front door, so a DuckDB 2.0
    * client's scan_data_from_quack_client source is set aside (TableExtractor.gatedUnsupported).
    * False denies it as an incomplete extraction.
    */
  def check(
      sql: String,
      kind: StatementKind,
      eff: EffectiveSet,
      ctx: SchemaContext,
      clientDataScans: Boolean = false
  ): GuardOutcome =
    val colPolicies = if clsEnabled then eff.columnPolicies else Nil
    val rowPolicies = if rlsEnabled then eff.rowPolicies else Nil
    if eff.user.tenant.isEmpty then Allow // superuser
    else if colPolicies.isEmpty && rowPolicies.isEmpty then Allow
    else
      // Every kind, a plain SELECT included: a table named in a string is invisible to the
      // rewriters, so the read would come back unfiltered and unmasked. A quoted name holding a
      // dot is matched by the rewriters as a qualified name, not as the one table DuckDB reads
      // (DottedIdentifierScreen). A SQL-valued setting runs SQL nobody reads, and a batch that
      // moves the schema or search path resolves its bare names past the policies.
      DottedIdentifierScreen
        .find(sql)
        .orElse(CatalogReachingCalls.find(sql).map(CatalogReachingCalls.denyReason))
        .orElse(
          CatalogReachingCalls.sqlValuedSetting(sql).map(CatalogReachingCalls.settingDenyReason)
        )
        .orElse(SessionScopeScreen.find(sql)) match
        case Some(reason) => Deny(s"access denied: $reason")
        case None         =>
          val config =
            Config.forDuckDB(ctx.defaultDatabase, ctx.defaultSchema, ctx.attachedCatalogs)
          val results = SqlParser.extract(sql, config).statements
          ambiguousName(results).orElse(CatalogReachingCalls.readingTableMacro(sql, results)) match
            case Some(reason) => Deny(s"access denied: $reason")
            case None if kind == StatementKind.Select && !isBatch(sql) => Allow
            case None                                                  =>
              checkWrite(sql, results, eff, ctx, colPolicies, rowPolicies, clientDataScans)

  /** The first two-part name headed by an attached catalog, in any statement. The policy match keys
    * it on the session catalog with the head as its schema, while DuckDB reads the head as the
    * catalog and the table from the current schema: the protected table a bare name reads. Refused
    * whatever the table, since which table DuckDB binds is not known here.
    */
  private def ambiguousName(results: List[StatementResult]): Option[String] =
    results.iterator
      .collect { case e: StatementResult.Extracted => e.qualificationErrors }
      .flatten
      .collectFirst { case a: DenyReason.AmbiguousCatalogRef =>
        ProtectedWriteGuard.ambiguousNameReason(a)
      }

  /** More than one statement, as the lexer separates them (a `;` inside a literal, a quoted name or
    * a comment does not count, nor does a trailing one). `kind` describes only a batch's first
    * statement, and the node runs all of them: `SELECT 1; INSERT INTO t SELECT c_email FROM
    * customer` classifies Select, and the rewriters, whose parse keeps only the first statement,
    * pass it through. So a batch is never waved through as a read: every statement is analysed like
    * a write's, a read of a protected table in any of them included.
    */
  private def isBatch(sql: String): Boolean =
    SqlCommentStripper.splitStatements(sql).lengthCompare(1) > 0

  private def checkWrite(
      sql: String,
      results: List[StatementResult],
      eff: EffectiveSet,
      ctx: SchemaContext,
      colPolicies: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy],
      rowPolicies: List[ai.starlake.quack.ondemand.state.RoleRowPolicy],
      clientDataScans: Boolean
  ): GuardOutcome =
    // Re-parse the same stripped text with the same feature config so the ASTs line up 1:1 with
    // the extractor's per-statement results by index. Anything that breaks that pairing is a read
    // we cannot isolate, so it denies.
    parseAsts(sql) match
      case Some(asts) if asts.size == results.size =>
        evaluate(results.zip(asts), eff, ctx, colPolicies, rowPolicies, clientDataScans)
      case _ => Deny(IncompleteDeny)

  private def evaluate(
      pairs: List[(StatementResult, Statement)],
      eff: EffectiveSet,
      ctx: SchemaContext,
      colPolicies: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy],
      rowPolicies: List[ai.starlake.quack.ondemand.state.RoleRowPolicy],
      clientDataScans: Boolean
  ): GuardOutcome =
    val rowKeys = rowPolicies.map(p => (p.catalogName, p.schemaName, p.tableName))
    val colKeys = colPolicies.map(p => (p.catalogName, p.schemaName, p.tableName))

    def covers(keys: List[(String, String, String)], t: TableRef): Boolean =
      keys.exists { case (c, s, tb) =>
        PolicyCoverage.covers(c, s, tb, t.database, t.schema, t.table)
      }

    // A plain read in a batch is denied with its own reason: the guard, not the rewriters, is
    // what stops it (see isBatch), and "read into a write statement" would not describe it.
    def denial(ast: Statement): String => String = ast match
      case _: Select if pairs.lengthCompare(1) > 0 => batchDeny
      case _                                       => deny

    // First statement that denies wins; Allow only when every statement is clean.
    val firstDeny = pairs.iterator
      .map { case (result, ast) =>
        evaluateOne(result, ast, eff, ctx, rowKeys, colKeys, covers, clientDataScans, denial(ast))
      }
      .collectFirst { case d: Deny => d }
    firstDeny.getOrElse(Allow)

  private def evaluateOne(
      result: StatementResult,
      ast: Statement,
      eff: EffectiveSet,
      ctx: SchemaContext,
      rowKeys: List[(String, String, String)],
      colKeys: List[(String, String, String)],
      covers: (List[(String, String, String)], TableRef) => Boolean,
      clientDataScans: Boolean,
      deny: String => String
  ): GuardOutcome =
    result match
      case e: StatementResult.Extracted =>
        val reads =
          e.accesses.filter(_.verb == Verb.Read).map(_.table).toSet
        val rowHit = reads.exists(t => covers(rowKeys, t))
        val colHit = reads.exists(t => covers(colKeys, t))
        if rowHit then GuardOutcome.Deny(deny(firstCovered(reads, rowKeys)))
        else if colHit then preciseCls(ast, eff, ctx, firstCovered(reads, colKeys), deny)
        else if TableExtractor
            .gatedUnsupported(e.accesses, e.unsupported, clientDataScans)
            .nonEmpty ||
          e.qualificationErrors.nonEmpty
        then
          // Extraction is incomplete, so a protected read may be unaccounted for. Fail closed.
          GuardOutcome.Deny(IncompleteDeny)
        else GuardOutcome.Allow
      case _: StatementResult.ParseError  => GuardOutcome.Deny(IncompleteDeny)
      case _: StatementResult.ControlFlow => GuardOutcome.Allow

  /** CLS arm: allow only if this statement's inner SELECT masks nothing. */
  private def preciseCls(
      ast: Statement,
      eff: EffectiveSet,
      ctx: SchemaContext,
      protectedName: String,
      deny: String => String
  ): GuardOutcome =
    innerSelect(ast) match
      case Some(sel) =>
        oracle.rewrite(sel.toString, StatementKind.Select, eff, ctx).unsafeRunSync() match
          case ColumnPolicyRewriter.Passthrough => GuardOutcome.Allow
          case _                                => GuardOutcome.Deny(deny(protectedName))
      case None => GuardOutcome.Deny(deny(protectedName))

  /** This statement's inner read SELECT for the write shapes we model precisely, else None. */
  private def innerSelect(ast: Statement): Option[Select] =
    ast match
      // An INSERT ... VALUES exposes a Values node (a Select subtype), not a query the
      // oracle can mask. Exclude it so a VALUES that embeds a subquery read of a covered
      // table has no isolable query select and fails closed.
      case i: Insert =>
        Option(i.getSelect).filterNot(_.isInstanceOf[Values]).flatMap(withStatementCtes(i, _))
      case c: CreateTable => Option(c.getSelect)
      case v: CreateView  => Option(v.getSelect)
      case _              => None

  /** JSqlParser hangs a statement-level `WITH x AS (...) INSERT INTO t SELECT * FROM x` on the
    * Insert, not on its Select, so the bare Select reads `x` as a physical table while DuckDB binds
    * it to the CTE (audit F19). Re-attach those CTEs so the oracle sees what DuckDB reads. A Select
    * that carries its own WITH too has two scopes one list cannot model: None, so it denies.
    */
  private def withStatementCtes(i: Insert, sel: Select): Option[Select] =
    val outer = Option(i.getWithItemsList).map(_.asScala.toList).getOrElse(Nil)
    val inner = Option(sel.getWithItemsList).map(_.asScala.toList).getOrElse(Nil)
    if outer.isEmpty then Some(sel)
    else if inner.nonEmpty then None
    else Some(sel.withWithItemsList(outer.asJava))

  /** Re-parse the whole (stripped, lexer-canonical: the input `SqlParser.extract` parses) input
    * into its statement list, or None on any rendering or parse failure.
    */
  private def parseAsts(sql: String): Option[List[Statement]] =
    ai.starlake.sql.ParserInput.renderWithoutTimeTravel(sql).toOption.flatMap { input =>
      try
        val stmts = ai.starlake.sql.JSqlStatements.parseBatch(input.text, configure)
        Option(stmts).map(_.asScala.toList)
      catch case _: Throwable => None
    }

  private val configure: java.util.function.Consumer[CCJSqlParser] =
    (parser: CCJSqlParser) =>
      val fc = new FeatureConfiguration()
      fc.setValue(Feature.allowUnsupportedStatements, true): Unit
      parser.withConfiguration(fc): Unit

  private def firstCovered(
      reads: Set[TableRef],
      keys: List[(String, String, String)]
  ): String =
    reads
      .find(t =>
        keys.exists { case (c, s, tb) =>
          PolicyCoverage.covers(c, s, tb, t.database, t.schema, t.table)
        }
      )
      .map(_.table)
      .getOrElse("a protected table")

  private def deny(table: String): String =
    s"access denied: $table is protected by a column/row policy and cannot be read into a write statement; query it directly instead"

  private def batchDeny(table: String): String =
    s"access denied: $table is protected by a column/row policy and cannot be read in a multi-statement batch; send that query on its own"

  private val IncompleteDeny =
    "access denied: statement could not be fully analysed and reads a protected schema; query the table directly instead"

object ProtectedWriteGuard:

  /** A guard that admits everything: for specs isolating something else, never for production. */
  val disabled: ProtectedWriteGuard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map.empty),
      clsEnabled = false,
      rlsEnabled = false
    )

  /** The refusal of a two-part name headed by an attached catalog, worded once for every step that
    * refuses it (this guard and [[RewriteScope]]).
    */
  def ambiguousNameReason(a: DenyReason.AmbiguousCatalogRef): String =
    s"ambiguous two-part name '${a.tableName}': '${a.catalog}' is an attached catalog; " +
      s"qualify fully as '${a.catalog}.<schema>.<table>'"
