package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.{Config, TableRef}
import ai.starlake.acl.parser.{SqlParser, StatementResult, Verb}
import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import cats.effect.unsafe.implicits.global
import net.sf.jsqlparser.parser.{CCJSqlParser, CCJSqlParserUtil}
import net.sf.jsqlparser.parser.feature.{Feature, FeatureConfiguration}
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.Select

enum GuardOutcome:
  case Allow
  case Deny(reason: String)

/** Closes the CLS/RLS write-wrapping bypass (spec 2026-08-20-protected-write-guard). For a
  * non-SELECT statement from a principal with enforced policies, denies when the read side exposes
  * a protected table: any read of an RLS table, or a CLS table whose masked columns actually appear
  * in the read. Reads it cannot isolate deny fail-closed. Never rewrites; only allows or denies.
  */
final class ProtectedWriteGuard(
    columnPolicyRewriter: ColumnPolicyRewriter,
    clsEnabled: Boolean = false,
    rlsEnabled: Boolean = false
):

  import GuardOutcome._

  def check(sql: String, kind: StatementKind, eff: EffectiveSet, ctx: SchemaContext): GuardOutcome =
    val colPolicies = if clsEnabled then eff.columnPolicies else Nil
    val rowPolicies = if rlsEnabled then eff.rowPolicies else Nil
    if kind == StatementKind.Select then Allow
    else if eff.user.tenant.isEmpty then Allow // superuser
    else if colPolicies.isEmpty && rowPolicies.isEmpty then Allow
    else
      val config          = Config.forDuckDB(ctx.defaultDatabase, ctx.defaultSchema, Set.empty)
      val extraction      = SqlParser.extract(sql, config)
      val extracted       = extraction.statements.collect { case e: StatementResult.Extracted => e }
      val parseIncomplete =
        extraction.statements.exists(_.isInstanceOf[StatementResult.ParseError]) ||
          extracted.exists(e => e.unsupported.nonEmpty || e.qualificationErrors.nonEmpty)
      val reads = extracted.flatMap(_.accesses).filter(_.verb == Verb.Read).map(_.table).toSet

      def covered(pCat: String, pSch: String, pTab: String): Boolean =
        reads.exists(t => PolicyCoverage.covers(pCat, pSch, pTab, t.database, t.schema, t.table))

      val rowHit = rowPolicies.exists(p => covered(p.catalogName, p.schemaName, p.tableName))
      val colHit = colPolicies.exists(p => covered(p.catalogName, p.schemaName, p.tableName))

      if rowHit then
        Deny(denyReason(reads, rowPolicies.map(p => (p.catalogName, p.schemaName, p.tableName))))
      else if colHit then
        preciseCls(
          sql,
          eff,
          ctx,
          colPolicies.map(p => (p.catalogName, p.schemaName, p.tableName)),
          reads
        )
      else if parseIncomplete then
        Deny(
          "access denied: statement could not be fully analysed and reads a protected schema; query the table directly instead"
        )
      else Allow

  /** CLS arm: allow only if the write's single inner SELECT masks nothing. */
  private def preciseCls(
      sql: String,
      eff: EffectiveSet,
      ctx: SchemaContext,
      colKeys: List[(String, String, String)],
      reads: Set[TableRef]
  ): GuardOutcome =
    val protectedName = firstCovered(reads, colKeys)
    innerSelect(sql) match
      case Some(sel) =>
        columnPolicyRewriter
          .rewrite(sel.toString, StatementKind.Select, eff, ctx)
          .unsafeRunSync() match
          case ColumnPolicyRewriter.Passthrough => GuardOutcome.Allow
          case _                                => GuardOutcome.Deny(deny(protectedName))
      case None => GuardOutcome.Deny(deny(protectedName))

  /** The write's inner read SELECT for the shapes we model precisely, else None. */
  private def innerSelect(sql: String): Option[Select] =
    val parsed =
      try Some(CCJSqlParserUtil.parseStatements(SqlParser.stripTimeTravelClauses(sql), configure))
      catch case _: Throwable => None
    parsed
      .flatMap(sts => Option(sts.getStatements).map(_.iterator()).filter(_.hasNext).map(_.next()))
      .flatMap {
        case i: Insert      => Option(i.getSelect)
        case c: CreateTable => Option(c.getSelect)
        case v: CreateView  => Option(v.getSelect)
        case _              => None
      }

  private val configure: java.util.function.Consumer[CCJSqlParser] =
    (parser: CCJSqlParser) =>
      val fc = new FeatureConfiguration()
      fc.setValue(Feature.allowUnsupportedStatements, true): Unit
      parser.withConfiguration(fc): Unit

  private def firstCovered(reads: Set[TableRef], keys: List[(String, String, String)]): String =
    reads
      .find(t =>
        keys.exists { case (c, s, tb) =>
          PolicyCoverage.covers(c, s, tb, t.database, t.schema, t.table)
        }
      )
      .map(_.table)
      .getOrElse("a protected table")

  private def denyReason(reads: Set[TableRef], keys: List[(String, String, String)]): String =
    deny(firstCovered(reads, keys))

  private def deny(table: String): String =
    s"access denied: $table is protected by a column/row policy and cannot be read into a write statement; query it directly instead"

object ProtectedWriteGuard:
  val disabled: ProtectedWriteGuard =
    new ProtectedWriteGuard(
      new ColumnPolicyRewriter(new ColumnCatalog.MapCatalog(Map.empty)),
      clsEnabled = false,
      rlsEnabled = false
    )
