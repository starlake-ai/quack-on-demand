package ai.starlake.quack.edge.cls

import java.util.Locale
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import cats.effect.IO
import cats.syntax.traverse._
import ai.starlake.acl.parser.TableExtractor
import ai.starlake.sql.{ParserInput, TooDeep}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{ParenthesedSelect, PlainSelect, Select, SetOperationList}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Resolves the schema/catalog defaults in scope when parsing a SQL statement so the rewriter can
  * qualify bare table references (`customer` -> `acme_tpch.tpch1.customer`).
  *
  * `attachedCatalogs`: the catalogs attached on the pool's nodes (the tenant-db alias, federated
  * aliases, DuckDB's built-ins), as `ValidationContext.attachedCatalogs`. DuckDB binds a two-part
  * `X.t` catalog-first when `X` is one of them, so the protected-write guard refuses that name
  * instead of reading it schema-first (audit F20). Required: an empty set disables that refusal.
  */
/** `pool` is the pool the statement runs on: the column catalog reads there the columns of a table
  * only its nodes hold (a federated source, a `duckdb-file` or `memory` database). None (a caller
  * with no pool) leaves such a table without columns, which the rewriter refuses to mask through.
  */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema: Option[String],
    attachedCatalogs: Set[String],
    pool: Option[ai.starlake.quack.model.PoolKey] = None
)

object ColumnPolicyRewriter:
  sealed trait Outcome
  final case class Rewritten(sql: String) extends Outcome
  final case class Denied(reason: String) extends Outcome
  case object Passthrough                 extends Outcome

  /** The rewriter could not parse the SQL, so it cannot prove the masked columns absent. The router
    * DENIES it (fail-closed), tagged `parse_failed` on the `column_policy_rewrites_total` counter
    * so dashboards can tell "rewriter blind" from a policy deny.
    */
  case object PassthroughParseFailed extends Outcome

  /** The deny reason for a rewrite that would be printed from a parser input that is not the
    * caller's statement (DuckDB's `//` integer division reaches JSqlParser as `/`), so shipping it
    * would run a different statement.
    */
  private[cls] val InexactRefusal: String =
    "column policies cannot be applied to a statement using integer division (//); " +
      "split the statement or select the division without the masked columns"

  /** A policy no table matches: its table name holds a NUL, which no SQL identifier can. */
  private[cls] val InertPolicy: ai.starlake.quack.ondemand.state.RoleColumnPolicy =
    ai.starlake.quack.ondemand.state.RoleColumnPolicy(
      id = "qod-inert",
      roleId = "",
      catalogName = "\u0000",
      schemaName = "\u0000",
      tableName = "\u0000",
      columnName = "\u0000",
      action = ai.starlake.quack.ondemand.state.RoleColumnPolicy.ActionMask,
      transformSql = Some("NULL")
    )

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
  * [[ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Passthrough Passthrough]] without touching the
  * catalog or the inner rewriter. Operators opt out via `quack-on-demand.cls.enabled = false` (or
  * `QOD_CLS_ENABLED=false`).
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
      // Every parse below reads the lexer-canonical rendering, never the raw text: JSqlParser
      // tokenizes DuckDB's escape and dollar-quoted strings differently, and a table it cannot see
      // is a table no policy masks. A rendering refusal is input DuckDB rejects anyway.
      // A too-deep statement overflows the parse and resolver walks below: denied (TooDeep).
      // Guarded twice because the walks run both eagerly (here) and inside the IO's map.
      TooDeep.orElse(IO.pure(Denied(TooDeep.Reason))) {
        ParserInput.render(sql) match
          case Left(_)      => IO.pure(PassthroughParseFailed)
          case Right(input) =>
            rewriteCanonical(input.text, eff, ctx).map {
              // The rewrite is printed from the parser input, which is not the caller's statement
              // here (ParserInput.Rendered.exact): shipping it would run a different statement.
              case Rewritten(_) if !input.exact => Denied(InexactRefusal)
              case other                        => other
            }
      }

  private def rewriteCanonical(sql: String, eff: EffectiveSet, ctx: SchemaContext): IO[Outcome] =
    if readsOnlyCatalogFunctions(sql) then IO.pure(Passthrough)
    else
      buildSchema(sql, eff.user.tenant.getOrElse(""), ctx).map {
        case Left(reason) => Denied(reason)
        case Right(r)     =>
          TooDeep.orElse(Denied(TooDeep.Reason)) {
            val scoped = r.policies(eff.columnPolicies)
            // No policy covers a table of this statement. In Pass mode nothing is left to do; Deny
            // mode still refuses a table or column the resolver cannot find, so the resolver runs
            // with a policy no table can match.
            val policies =
              if scoped.nonEmpty || unresolvedMode == UnresolvedMode.Pass then scoped
              else List(InertPolicy)
            if policies.isEmpty then Passthrough
            else
              r.separation match
                case None      => rewriteResolved(sql, r.schema, r.homes, policies, ctx)
                case Some(sep) =>
                  rewriteResolved(sep.sql, r.schema, r.homes, policies, ctx) match
                    case Rewritten(out) =>
                      sep.restore(out).fold(Denied(_), Rewritten(_))
                    case Denied(reason) => Denied(sep.describe(reason))
                    case other          => other
          }
      }

  private def rewriteResolved(
      sql: String,
      schema: Map[String, List[String]],
      homes: Map[String, (String, String)],
      policies: List[ai.starlake.quack.ondemand.state.RoleColumnPolicy],
      ctx: SchemaContext
  ): Outcome =
    PositionalReferences.resolve(sql, schema) match
      case Left(reason) => Denied(reason)
      case Right(named) =>
        inner.rewrite(
          sql = named,
          schema = schema,
          policies = policies,
          defaultCatalog = ctx.defaultDatabase,
          defaultSchema = ctx.defaultSchema,
          unresolvedMode = unresolvedMode,
          homes = homes
        ) match
          case RewriteOutcome.Rewritten(s)   => Rewritten(s)
          case RewriteOutcome.Denied(reason) =>
            if looksUnresolvedTable(reason) then DeniedUnresolvedTable else Denied(reason)
          case RewriteOutcome.Passthrough => Passthrough
          case RewriteOutcome.ParseFailed => PassthroughParseFailed

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
    * to its first statement, so a shortcut judged on a batch's head would forward its tail
    * unmasked. The resolver below still parses only that first statement; what keeps a batch's tail
    * from going out unmasked is the protected-write guard, which runs before this rewriter and
    * analyses every statement of a multi-statement batch, denying it when any statement reads a
    * table under a column or row policy. Any other table function (`read_parquet`, ...) keeps the
    * fail-closed path, as does a catalog function beside a physical table.
    */
  private def readsOnlyCatalogFunctions(sql: String): Boolean =
    Try(CCJSqlParserUtil.parseStatements(sql)).toOption
      .filter(_.size == 1)
      .flatMap(sts => Option(sts.get(0)).collect { case sel: Select => sel })
      .exists { sel =>
        val extraction = TableExtractor.extract(sel)
        extraction.tables.isEmpty && extraction.unsupported.nonEmpty &&
        extraction.unsupported.forall(u =>
          TableExtractor.tableFunctionName(u).flatMap(TableExtractor.catalogFunctionCall).isDefined
        )
      }

  /** The statement's column lists by table key, each table named outside the defaults with where it
    * is named, and, when it reads tables of one bare name in several places, how they were renamed
    * apart ([[SameNameTables]]). `tables` is every table key with the catalog, schema and name it
    * reads, and `complete` whether the walk that found them read the whole statement.
    */
  private final case class Resolved(
      schema: Map[String, List[String]],
      homes: Map[String, (String, String)],
      separation: Option[SameNameTables.Separation],
      tables: List[(String, (String, String, String))] = Nil,
      complete: Boolean = false
  ):

    /** The policies the rewriter applies to this statement. The rewriter matches a policy to a
      * column by the bare name of the column's table, so a policy is handed to it only under the
      * key of a table it covers by catalog, schema and name (`PolicyCoverage`, the rule of the row
      * filters and the protected-write guard): a mask on `client_a.claims.claim` does not mask
      * `client_b.claims.claim` (issue #201). A policy whose table part is `*` stays as it is, as it
      * matches whatever table a column is attributed to, a column it cannot place included, once it
      * covers any table of the statement.
      *
      * When the walk did not read the whole statement (a construct it reports as unsupported, a
      * name it cannot place, a parse it could not make), a table it missed would lose its policies,
      * so every policy is applied by bare name, as before: over-masking, never a leak.
      */
    def policies(all: List[RoleColumnPolicy]): List[RoleColumnPolicy] =
      if !complete then separation.map(_.policies(all)).getOrElse(all)
      else
        tables.flatMap { case (key, (cat, sch, tab)) =>
          all.collect {
            case p
                if ai.starlake.quack.edge.policy.PolicyCoverage
                  .covers(p.catalogName, p.schemaName, p.tableName, cat, sch, tab) =>
              if p.tableName == RoleColumnPolicy.Wildcard then p else p.copy(tableName = key)
          }
        }.distinct

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
  private def buildSchema(
      sql: String,
      tenant: String,
      ctx: SchemaContext
  ): IO[Either[String, Resolved]] =
    Try(ai.starlake.sql.JSqlStatements.parseFirst(sql)) match
      case Failure(_)            => IO.pure(Right(Resolved(Map.empty, Map.empty, None)))
      case Success(stmt: Select) =>
        val (found, complete) = collectTables(stmt, ctx)
        val tables            = found.filterNot { case (_, (_, sch, _)) =>
          SystemSchemaColumns.isSystemSchema(sch) // resolved via SystemSchemaColumns inside
        }
        val clashing = SameNameTables.clashing(tables)
        // Tables of one bare name in several catalogs or schemas are renamed apart first, so
        // each reads its own column list (issue #201).
        val separated =
          if clashing.isEmpty then Right(None)
          else SameNameTables.separate(stmt, tables, clashing).map(Some(_))
        separated match
          case Left(reason) => IO.pure(Left(reason))
          case Right(sep)   =>
            val kept = tables.collect {
              case (t, coords @ (_, _, tab)) if !clashing.contains(tab.toLowerCase(Locale.ROOT)) =>
                (tab, coords)
            }
            val keyed = kept ++ sep.toList.flatMap(_.keys)
            keyed.distinct
              .traverse { case (key, (cat, sch, tab)) =>
                catalog.columnsOf(tenant, ctx.pool, cat, sch, tab).map(cols => key -> cols)
              }
              .map { listed =>
                val homes = kept.map { case (key, (cat, sch, _)) => key -> (cat, sch) }.toMap
                Right(Resolved(listed.toMap, homes, sep, keyed.distinct, complete))
              }
      case Success(_) => IO.pure(Right(Resolved(Map.empty, Map.empty, None)))

  /** Every physical table referenced ANYWHERE in the statement, with its bare name: the ACL
    * parser's walk ([[TableExtractor]]), which reaches FROM items, joins, CTE bodies, set-op arms
    * AND expression subqueries (a table the map never mentions can neither resolve nor deny), and
    * binds CTE names as DuckDB does. JSqlParser's own TablesNamesFinder dropped every table named
    * like a CTE of the statement, so the dbt shape `WITH customer AS (SELECT * FROM customer)
    * SELECT c_email FROM customer`, whose body reads the real table, could not be resolved and was
    * denied. A name missing a catalog or schema gets the session's. The flag is false when the walk
    * failed, reported a construct it does not read, or met a name of more than three parts.
    */
  private def collectTables(
      stmt: Select,
      ctx: SchemaContext
  ): (List[(net.sf.jsqlparser.schema.Table, (String, String, String))], Boolean) =
    val extraction         = Try(TableExtractor.extract(stmt)).toOption
    val tables             = extraction.map(_.tables).getOrElse(Nil)
    def unquote(s: String) = ai.starlake.sql.QuotedIdentifier.unquote(s)
    val placed             = tables.flatMap { t =>
      t.getNameParts.asScala.toList.reverse.map(unquote) match
        case tab :: Nil =>
          Some(t -> (ctx.defaultDatabase.getOrElse(""), ctx.defaultSchema.getOrElse(""), tab))
        case sch :: tab :: Nil =>
          Some(t -> (ctx.defaultDatabase.getOrElse(""), sch, tab))
        case cat :: sch :: tab :: Nil =>
          Some(t -> (cat, sch, tab))
        case _ => None
    }
    val complete = extraction.exists(_.unsupported.isEmpty) && placed.size == tables.size
    (placed, complete)

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
        .map(_.asScala.toList) match
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
                Try(walk.getTables(ps: net.sf.jsqlparser.statement.Statement)).toOption match
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
