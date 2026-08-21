package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.{Config, TableRef}
import ai.starlake.acl.parser.{SqlParser, StatementResult, Verb}
import ai.starlake.quack.edge.cls.{
  ColumnCatalog,
  ColumnPolicyRewriter,
  SchemaContext,
  UnresolvedMode
}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
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
  * non-SELECT statement from a principal with enforced policies, denies when the read side exposes
  * a protected table: any read of an RLS table, or a CLS table whose masked columns actually appear
  * in the read. Any read it cannot isolate denies fail-closed. Never rewrites; only allows or
  * denies.
  *
  * The guard owns its CLS detection oracle: it builds a `ColumnPolicyRewriter` internally in
  * `UnresolvedMode.Deny`, so a table whose columns the catalog cannot enumerate denies rather than
  * passing through unexpanded. Analysis is per statement: a batch is allowed only when every
  * statement is clean, so a benign write cannot launder a protected read in a sibling statement.
  */
final class ProtectedWriteGuard(
    catalog: ColumnCatalog,
    clsEnabled: Boolean = false,
    rlsEnabled: Boolean = false
):

  import GuardOutcome._

  // Deny-mode oracle: an unresolvable protected table must deny, not pass through.
  private val oracle =
    new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny, enabled = true)

  def check(sql: String, kind: StatementKind, eff: EffectiveSet, ctx: SchemaContext): GuardOutcome =
    val colPolicies = if clsEnabled then eff.columnPolicies else Nil
    val rowPolicies = if rlsEnabled then eff.rowPolicies else Nil
    if kind == StatementKind.Select then Allow
    else if eff.user.tenant.isEmpty then Allow // superuser
    else if colPolicies.isEmpty && rowPolicies.isEmpty then Allow
    else
      val config  = Config.forDuckDB(ctx.defaultDatabase, ctx.defaultSchema, Set.empty)
      val results = SqlParser.extract(sql, config).statements
      // Re-parse the same stripped text with the same feature config so the ASTs line up 1:1 with
      // the extractor's per-statement results by index. Anything that breaks that pairing is a read
      // we cannot isolate, so it denies.
      parseAsts(sql) match
        case Some(asts) if asts.size == results.size =>
          evaluate(results.zip(asts), eff, ctx, colPolicies, rowPolicies)
        case _ => Deny(IncompleteDeny)

  private def evaluate(
      pairs: List[(StatementResult, Statement)],
      eff: EffectiveSet,
      ctx: SchemaContext,
      colPolicies: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy],
      rowPolicies: List[ai.starlake.quack.ondemand.state.RoleRowPolicy]
  ): GuardOutcome =
    val rowKeys = rowPolicies.map(p => (p.catalogName, p.schemaName, p.tableName))
    val colKeys = colPolicies.map(p => (p.catalogName, p.schemaName, p.tableName))

    def covers(keys: List[(String, String, String)], t: TableRef): Boolean =
      keys.exists { case (c, s, tb) =>
        PolicyCoverage.covers(c, s, tb, t.database, t.schema, t.table)
      }

    // First statement that denies wins; Allow only when every statement is clean.
    val firstDeny = pairs.iterator
      .map { case (result, ast) => evaluateOne(result, ast, eff, ctx, rowKeys, colKeys, covers) }
      .collectFirst { case d: Deny => d }
    firstDeny.getOrElse(Allow)

  private def evaluateOne(
      result: StatementResult,
      ast: Statement,
      eff: EffectiveSet,
      ctx: SchemaContext,
      rowKeys: List[(String, String, String)],
      colKeys: List[(String, String, String)],
      covers: (List[(String, String, String)], TableRef) => Boolean
  ): GuardOutcome =
    result match
      case e: StatementResult.Extracted =>
        val reads =
          e.accesses.filter(_.verb == Verb.Read).map(_.table).toSet
        val rowHit = reads.exists(t => covers(rowKeys, t))
        val colHit = reads.exists(t => covers(colKeys, t))
        if rowHit then GuardOutcome.Deny(deny(firstCovered(reads, rowKeys)))
        else if colHit then preciseCls(ast, eff, ctx, firstCovered(reads, colKeys))
        else if e.unsupported.nonEmpty || e.qualificationErrors.nonEmpty then
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
      protectedName: String
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
      case i: Insert      => Option(i.getSelect).filterNot(_.isInstanceOf[Values])
      case c: CreateTable => Option(c.getSelect)
      case v: CreateView  => Option(v.getSelect)
      case _              => None

  /** Re-parse the whole (stripped) input into its statement list, or None on any parse failure. */
  private def parseAsts(sql: String): Option[List[Statement]] =
    try
      val stmts = CCJSqlParserUtil.parseStatements(SqlParser.stripTimeTravelClauses(sql), configure)
      Option(stmts.getStatements).map(_.asScala.toList)
    catch case _: Throwable => None

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

  private val IncompleteDeny =
    "access denied: statement could not be fully analysed and reads a protected schema; query the table directly instead"

object ProtectedWriteGuard:
  val disabled: ProtectedWriteGuard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map.empty),
      clsEnabled = false,
      rlsEnabled = false
    )
