package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import ai.starlake.transpiler.JSQLColumResolver
import ai.starlake.transpiler.schema.JdbcMetaData
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.{PlainSelect, Select, SelectItem}

final class JsqltranspilerRewriter extends SchemaAwareSqlRewriter:

  import RewriteOutcome._

  private final case class DenyException(reason: String) extends RuntimeException(reason)

  def rewrite(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      defaultCatalog: Option[String],
      defaultSchema: Option[String],
      unresolvedMode: UnresolvedMode = UnresolvedMode.Deny
  ): RewriteOutcome =
    if policies.isEmpty then return Passthrough

    val schemaDef: Array[Array[String]] = schema.toArray.map { case (tableKey, cols) =>
      (tableKey +: cols).toArray
    }

    // Use the 3-arg constructor so SQL schema qualifiers (e.g. `tpch1.customer`) match the
    // schemaless rows in `schemaDef`. The 1-arg form leaves currentSchema="" and silently
    // mismatches every schema-qualified table ref. defaultCatalog/defaultSchema come from the
    // caller's SchemaContext (the session-defaults pinned at handshake time).
    val resolver = new JSQLColumResolver(
      defaultCatalog.getOrElse(""),
      defaultSchema.getOrElse(""),
      schemaDef
    )
    resolver.setErrorMode(unresolvedMode match
      case UnresolvedMode.Deny        => JdbcMetaData.ErrorMode.STRICT
      case UnresolvedMode.Passthrough => JdbcMetaData.ErrorMode.LENIENT)

    val resolvedText: String =
      try resolver.getResolvedStatementText(sql)
      catch
        // jsqltranspiler 1.9 does NOT ship a single `JSQLDataException` umbrella; it raises one
        // of the table/column/schema/catalog-not-found exceptions under `ai.starlake.transpiler.*`.
        // Treat any of these as the "unknown coordinate" case the spec calls out, and let the
        // unresolvedMode arm decide deny vs passthrough.
        case e: ai.starlake.transpiler.TableNotFoundException    => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.TableNotDeclaredException => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.ColumnNotFoundException   => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.SchemaNotFoundException   => return Denied(e.getMessage)
        case e: ai.starlake.transpiler.CatalogNotFoundException  => return Denied(e.getMessage)
        case _: net.sf.jsqlparser.JSQLParserException            => return ParseFailed
        case _: Throwable                                        => return ParseFailed

    val rsMeta                                          = resolver.getResultSetMetaData(sql)
    val projectionOrigins: IndexedSeq[(String, String)] =
      (1 to rsMeta.getColumnCount).toIndexedSeq.map { i =>
        (
          Option(rsMeta.getTableName(i)).getOrElse(""),
          Option(rsMeta.getColumnName(i)).getOrElse("")
        )
      }

    val parsed =
      try CCJSqlParserUtil.parse(resolvedText)
      catch case _: Throwable => return ParseFailed

    parsed match
      case sel: Select =>
        try
          val (changed, _) = applyPolicies(sel, projectionOrigins, policies)
          if changed then Rewritten(sel.toString) else Passthrough
        catch case e: DenyException => Denied(e.reason)
      case _ =>
        Passthrough

  /** Walk the resolved statement's projection items and replace any Column whose physical (table,
    * column) lineage matches a policy. Full visitor surface (CASE / CAST / OVER / IN / BETWEEN /
    * EXTRACT / WHERE / HAVING / GROUP / ORDER) is added in Task 5.
    */
  private def applyPolicies(
      sel: Select,
      origins: IndexedSeq[(String, String)],
      policies: List[RoleColumnPolicy]
  ): (Boolean, Select) =
    val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
    sel match
      case ps: PlainSelect =>
        val items = ps.getSelectItems
        if items != null then
          val it  = items.listIterator()
          var idx = 0
          while it.hasNext do
            val si           = it.next()
            val (table, col) = if origins.indices.contains(idx) then origins(idx) else ("", "")
            matchingPolicy(table, col, policies) match
              case Some(p) if p.action == RoleColumnPolicy.ActionDeny =>
                throw DenyException(s"column $table.$col is denied")
              case Some(p) =>
                val replacement = CCJSqlParserUtil.parseExpression(p.transformSql.get)
                si.asInstanceOf[SelectItem[Expression]].setExpression(replacement)
                changed.set(true)
              case None => ()
            idx += 1
      case _ => ()
    (changed.get, sel)

  private def matchingPolicy(
      table: String,
      column: String,
      policies: List[RoleColumnPolicy]
  ): Option[RoleColumnPolicy] =
    policies.find { p =>
      (p.tableName == RoleColumnPolicy.Wildcard || p.tableName.equalsIgnoreCase(table)) &&
      p.columnName.equalsIgnoreCase(column)
    }
