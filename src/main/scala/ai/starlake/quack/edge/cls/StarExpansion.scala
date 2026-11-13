package ai.starlake.quack.edge.cls

import ai.starlake.acl.parser.AstChildren
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import net.sf.jsqlparser.expression.{Alias, ColumnsTransformer, Expression}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select.{
  AllColumns,
  AllTableColumns,
  FromItem,
  PlainSelect,
  Select,
  SelectItem
}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** DuckDB's star modifiers expanded to an explicit column list before the column resolver runs.
  *
  * The resolver expands a plain `*` from the catalog, but drops `* EXCLUDE (..)` and
  * `* REPLACE (.. AS ..)` on the floor (the excluded columns came back, masked; a replacement was
  * replaced by the masked base column), and qualifies an aliased table's columns by the table name
  * (`SELECT customer.c_id .. FROM customer c`), which DuckDB rejects. Here such a star is replaced
  * by the columns it stands for, read from the same catalog the plain expansion uses, each
  * qualified by the table's binding name (its alias, else its name as written): EXCLUDE drops a
  * column, REPLACE puts its expression in the column's slot under the column's name, so the masking
  * visitor sees a plain projection and masks it like any other.
  *
  * Only shapes whose column list is exact are expanded: `t.*` naming one base table of the FROM
  * clause, `*` over a FROM clause of exactly one base table, or an unmodified `*` over a join of
  * base tables only (no USING, NATURAL, SEMI or ANTI join, which merge or drop columns; issue
  * #217), the tables' columns known and their names not a CTE of the statement, and only modifiers
  * whose result is exact: a column-list EXCLUDE / EXCEPT and REPLACE. A star carrying any other
  * modifier (RENAME, APPLY, a pattern EXCLUDE), an alias, or one that cannot be expanded is refused
  * when the select reads a table a column policy names; elsewhere it is left to the resolver, which
  * masks nothing there. The pattern forms (`* LIKE`, COLUMNS(..)) are not modifiers: the residual
  * check refuses them.
  */
private[cls] object StarExpansion:

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  private def lower(s: String): String = s.toLowerCase(java.util.Locale.ROOT)

  private def quoteIfNeeded(name: String): String =
    if name.matches("[a-z_][a-z0-9_]*") then name
    else "\"" + name.replace("\"", "\"\"") + "\""

  private val Refusal =
    "a star with EXCLUDE / REPLACE cannot be expanded over this FROM clause under column " +
      "policies; name the columns instead"

  private def transformers(star: AllColumns): List[ColumnsTransformer] =
    Option(star.getTransformers).map(_.asScala.toList).getOrElse(Nil)

  /** A star with no modifier (EXCLUDE, REPLACE, RENAME, ..): exactly the columns it stands for. */
  private[cls] def unmodified(star: AllColumns): Boolean = transformers(star).isEmpty

  // JSqlParser splits a quoted column name on its dots when the expansion is parsed again
  // (`c."price.usd"` came out as `"price"."usd"`, another column), so it cannot be printed.
  private val DottedColumnRefusal =
    "a star over a table with a column whose name holds a dot cannot be expanded under column " +
      "policies; name the columns instead"

  // The same split turns a column qualified by a dotted alias (`"x.y".c_email`) into one that binds
  // nowhere: the resolver masks nothing and the caller's own star would be sent (issue #217 review).
  private val DottedAliasRefusal =
    "a star over a table whose alias or name holds a dot cannot be expanded under column " +
      "policies; use an alias without a dot, or name the columns"

  /** Right(the statement to resolve: `sql` itself when no star was expanded), or Left(reason). */
  def expand(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy]
  ): Either[String, String] =
    Try(ai.starlake.sql.JSqlStatements.parseFirst(sql)).toOption match
      case Some(sel: Select) =>
        val selects = plainSelects(sel)
        val ctes    = cteNames(sel)
        val results = selects.map(expandIn(_, schema, policies, ctes))
        results.collectFirst { case Left(reason) => reason } match
          case Some(reason) => Left(reason)
          case None         => Right(if results.contains(Right(true)) then sel.toString else sql)
      case _ => Right(sql)

  /** Every AST node of `root`, at any depth, children JSqlParser keeps in collections included. */
  private[cls] def nodes(root: AnyRef): List[AnyRef] =
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    val out                    = scala.collection.mutable.ListBuffer.empty[AnyRef]
    def walk(value: Any): Unit =
      value match
        case null                         => ()
        case it: java.lang.Iterable[?]    => it.forEach(walk(_))
        case arr: Array[?]                => arr.foreach(walk)
        case e: java.util.Map.Entry[?, ?] => walk(e.getKey); walk(e.getValue)
        case m: java.util.Map[?, ?]       => m.entrySet.forEach(walk(_))
        case n: AnyRef if AstChildren.isAstNode(n) && seen.add(n) =>
          out += n
          AstChildren.accessors(n.getClass).foreach(m => walk(AstChildren.read(m, n)))
        case _ => ()
    walk(root)
    out.toList

  private[cls] def plainSelects(root: AnyRef): List[PlainSelect] =
    nodes(root).collect { case ps: PlainSelect => ps }

  /** Every CTE name of the statement, whatever its scope: a star over one is never expanded. */
  private def cteNames(root: AnyRef): Set[String] =
    nodes(root)
      .collect { case wi: net.sf.jsqlparser.statement.select.WithItem[?] =>
        Option(wi.getUnquotedAliasName).map(lower)
      }
      .flatten
      .toSet

  /** Right(true) when a star of `ps` was expanded in place, Right(false) when none needed it. */
  private def expandIn(
      ps: PlainSelect,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      ctes: Set[String]
  ): Either[String, Boolean] =
    val joins     = Option(ps.getJoins).map(_.asScala.toList).getOrElse(Nil)
    val fromItems = (Option(ps.getFromItem).toList ++ joins.map(_.getFromItem)).filter(_ != null)
    def binding(fi: FromItem): Option[String] =
      Option(fi.getAlias)
        .map(_.getName)
        .orElse(fi match
          case t: Table => Option(t.getName)
          case _        => None)
    def columnsOf(t: Table): Option[List[String]] =
      val name    = unquote(Option(t.getName).getOrElse(""))
      val renames = Option(t.getAlias).flatMap(a => Option(a.getAliasColumns)).exists(!_.isEmpty)
      val pivoted = t.getPivot != null || t.getUnPivot != null
      if ctes.contains(lower(name)) || renames || pivoted then None
      else schema.find(_._1.equalsIgnoreCase(name)).map(_._2).filter(_.nonEmpty)
    def isPolicyTable(t: Table): Boolean =
      val name = unquote(Option(t.getName).getOrElse(""))
      policies.exists(p =>
        p.tableName == RoleColumnPolicy.Wildcard || p.tableName.equalsIgnoreCase(name)
      )
    val policyScope = fromItems.exists {
      case t: Table => isPolicyTable(t)
      case _        => false
    }
    // A bare `*` over several base tables is each table's columns in FROM order, unless a join
    // merges or drops columns (USING, NATURAL, SEMI, ANTI) or two tables share a binding name.
    lazy val joinedTables: Option[List[Table]] =
      val tables   = fromItems.collect { case t: Table => t }
      val bindings = tables.flatMap(binding).map(b => lower(unquote(b)))
      val plain    = joins.forall { j =>
        !j.isNatural && !j.isSemi && !j.isAnti &&
        Option(j.getUsingColumns).forall(_.isEmpty)
      }
      Option.when(
        tables.size > 1 && tables.size == fromItems.size && plain &&
          bindings.distinct.size == tables.size
      )(tables)
    val dottedBinding = fromItems.exists(fi => binding(fi).exists(_.contains('.')))
    val items         = Option(ps.getSelectItems).map(_.asScala.toList).getOrElse(Nil)
    val expanded: List[Either[String, Option[List[SelectItem[?]]]]] = items.map { si =>
      si.getExpression match
        case _: AllColumns if dottedBinding =>
          if policyScope then Left(DottedAliasRefusal) else Right(None)
        case star: AllColumns =>
          val modified                     = !unmodified(star) || si.getAlias != null
          val targets: Option[List[Table]] = star match
            case atc: AllTableColumns =>
              val q = Option(atc.getTable)
                .filter(_.getSchemaName == null)
                .map(t => lower(unquote(t.getName)))
              q.flatMap { name =>
                fromItems.filter(fi => binding(fi).exists(b => lower(unquote(b)) == name)) match
                  case List(t: Table) => Some(List(t))
                  case _              => None
              }
            case _ =>
              fromItems match
                case List(t: Table) => Some(List(t))
                case _              => joinedTables.filter(_ => !modified)
          val aliased = targets.exists(_.exists(_.getAlias != null))
          if !modified && !aliased then Right(None)
          else
            val known =
              targets.map(_.map(t => columnsOf(t).map(t -> _))).filter(_.forall(_.isDefined))
            known.map(_.flatten) match
              case Some(pairs) if si.getAlias == null =>
                val lists = pairs.map((t, cols) => columnList(star, t, cols))
                // A join's star is expanded beyond the policy scope only to fix its qualifiers, so
                // what it cannot expand there it leaves as written.
                lists.collectFirst { case Left(reason) => reason } match
                  case Some(_) if pairs.size > 1 && !policyScope => Right(None)
                  case Some(reason)                              => Left(reason)
                  case None => Right(Some(lists.flatMap(_.toOption).flatten))
              case _ => if modified && policyScope then Left(Refusal) else Right(None)
        case _ => Right(None)
    }
    expanded.collectFirst { case Left(reason) => reason } match
      case Some(reason) => Left(reason)
      case None         =>
        if expanded.forall(_ == Right(None)) then Right(false)
        else
          val next = new java.util.ArrayList[SelectItem[?]]()
          items.lazyZip(expanded).foreach { (si, e) =>
            e match
              case Right(Some(list)) => list.foreach(next.add)
              case _                 => next.add(si)
          }
          ps.setSelectItems(next)
          Right(true)

  /** The explicit select items `star` stands for over `t`, whose catalog columns are `cols`. */
  private def columnList(
      star: AllColumns,
      t: Table,
      cols: List[String]
  ): Either[String, List[SelectItem[?]]] =
    val bindingName = Option(t.getAlias).map(_.getName).getOrElse(t.getName)
    val known       = cols.map(lower).toSet
    import ColumnsTransformer.ColumnsTransformerType as Kind
    val ts = transformers(star)
    // Only a column-list EXCLUDE / EXCEPT and REPLACE have an exact expansion; RENAME, APPLY and a
    // pattern exclusion are refused rather than approximated.
    val expandable = ts.forall { t =>
      t.getType match
        case Kind.EXCEPT | Kind.EXCLUDE => t.getExceptPattern == null && t.getExceptColumns != null
        case Kind.REPLACE               => t.getReplaceItems != null
        case _                          => false
    }
    val excepts = ts
      .filter(t => t.getType == Kind.EXCEPT || t.getType == Kind.EXCLUDE)
      .flatMap(t => Option(t.getExceptColumns).map(_.asScala.toList).getOrElse(Nil))
    val replaces = ts
      .filter(_.getType == Kind.REPLACE)
      .flatMap(t => Option(t.getReplaceItems).map(_.asScala.toList).getOrElse(Nil))
    val excluded = excepts.map { c =>
      val qualifierOk = Option(c.getTable)
        .flatMap(q => Option(q.getName))
        .forall(q => lower(unquote(q)) == lower(unquote(bindingName)))
      val name = lower(unquote(Option(c.getColumnName).getOrElse("")))
      Option.when(qualifierOk && known.contains(name))(name)
    }
    val replaced = replaces.map { r =>
      Option(r.getAlias)
        .map(a => lower(unquote(a.getName)))
        .filter(known.contains)
        .map(_ -> r.getExpression.asInstanceOf[Expression])
    }
    val drop = excluded.flatten.toSet
    val kept = cols.filterNot(c => drop.contains(lower(c)))
    if !expandable || excluded.contains(None) || replaced.contains(None) then Left(Refusal)
    else if kept.exists(_.contains('.')) then Left(DottedColumnRefusal)
    else
      val subs = replaced.flatten.toMap
      Right(kept.map { c =>
        subs.get(lower(c)) match
          case Some(expr) => new SelectItem[Expression](expr, new Alias(quoteIfNeeded(c)))
          case None => new SelectItem[Column](new Column(new Table(bindingName), quoteIfNeeded(c)))
      })
