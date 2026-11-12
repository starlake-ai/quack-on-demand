package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import net.sf.jsqlparser.expression.Alias
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select.{AllTableColumns, FromItem, ParenthesedFromItem, Select}

import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Tables of one bare name read from different catalogs or schemas in one statement
  * (`client_a.claims.claim JOIN client_b.claims.claim`, issue #201).
  *
  * The masking rewriter keys a table by its bare name, so two such tables would share one column
  * list: a star of one would expand to the other's columns. Before the rewrite each such table is
  * given a name of its own, `qod_same_<nonce>_<n>`, bare, aliased by its original bare name when
  * the caller gave it no alias, so every reference the caller wrote still binds where it did; the
  * rewriter then reads one column list per table. Afterwards every table so renamed gets its name
  * back, and the alias added is removed, so the statement sent differs from the caller's only by
  * its masked expressions.
  *
  * Which policies cover a renamed table does not change: the rewriter matches a policy on the bare
  * table name, so each renamed table gets a copy of every policy naming its bare name, exactly what
  * it gets when read alone. Two such tables therefore carry the same policies, and which of them a
  * column is attributed to never decides whether it is masked.
  *
  * Refused, as the binding cannot be read by name alone:
  *   - two such tables, or one and another source, bound to one name in one FROM clause (DuckDB
  *     admits `FROM a.s.claim, b.s.claim` and binds each column by its catalog-qualified name);
  *   - a column or a star of such a table qualified by its schema or catalog
  *     (`a.s.claim.member_no`, `a.s.claim.*`).
  */
private[cls] object SameNameTables:

  /** A statement prepared for the rewriter: `sql` with the tables renamed, `keys` the name each
    * renamed table now has with where its columns are read, `renamed` each new name with the
    * caller's spelling of the table.
    */
  final class Separation private[SameNameTables] (
      val sql: String,
      val keys: List[(String, (String, String, String))],
      private val marker: String,
      private val renamed: Map[String, Renamed]
  ):

    /** The caller's policies plus, for each renamed table, a copy of every one naming its bare
      * name, under the table's new name.
      */
    def policies(all: List[RoleColumnPolicy]): List[RoleColumnPolicy] =
      all ++ renamed.toList.flatMap { case (key, r) =>
        all.collect {
          case p
              if p.tableName != RoleColumnPolicy.Wildcard &&
                unquote(p.tableName).equalsIgnoreCase(r.bare) =>
            p.copy(tableName = key)
        }
      }

    /** The rewriter's output with every renamed table named as the caller wrote it; Left when any
      * new name is left in it, which would then not run as the caller's statement.
      */
    def restore(rewritten: String): Either[String, String] =
      Try(ai.starlake.sql.JSqlStatements.parseFirst(rewritten)).toOption match
        case None         => Left(Unrestorable)
        case Some(parsed) =>
          val nodes = StarExpansion.nodes(parsed)
          // A qualifier the rewriter wrote (a star it expanded: `<table>.id`) names the table by
          // the name it binds to in its FROM clause, its alias, never by its catalog-qualified name,
          // which DuckDB does not bind once the table has an alias.
          def requalify(q: Table): Unit =
            Option(q)
              .flatMap(t => Option(t.getName))
              .map(unquote)
              .flatMap(n => renamed.get(lower(n)))
              .foreach(r => q.setNameParts(new java.util.ArrayList(java.util.List.of(r.binding))))
          nodes.foreach {
            case c: Column          => requalify(c.getTable)
            case s: AllTableColumns => requalify(s.getTable)
            case _                  => ()
          }
          nodes.foreach {
            case t: Table =>
              Option(t.getName).map(unquote).flatMap(n => renamed.get(lower(n))).foreach { r =>
                t.setNameParts(new java.util.ArrayList(r.parts.asJava))
                if r.aliasAdded && Option(t.getAlias).exists(_.getName == r.rawBare) then
                  t.setAlias(null)
              }
            case _ => ()
          }
          val builder = new java.lang.StringBuilder()
          parsed.accept(new net.sf.jsqlparser.util.deparser.StatementDeParser(builder))
          val out = builder.toString
          if lower(out).contains(marker) then Left(Unrestorable) else Right(out)

    /** A refusal's text with each new name read as the caller wrote it. */
    def describe(reason: String): String =
      renamed.foldLeft(reason) { case (text, (key, r)) =>
        text.replaceAll(
          "(?i)" + java.util.regex.Pattern.quote(key),
          java.util.regex.Matcher.quoteReplacement(r.written)
        )
      }

  private final case class Renamed(
      parts: List[String],
      rawBare: String,
      bare: String,
      written: String,
      binding: String,
      aliasAdded: Boolean
  )

  private[cls] val Unrestorable: String =
    "column policies could not be applied to this statement; read the tables in separate statements"

  private[cls] def sameFromClauseRefusal(name: String): String =
    s"two tables named $name in one FROM clause need aliases under column policies " +
      s"(FROM client_a.s.$name a JOIN client_b.s.$name b)"

  private[cls] def qualifiedColumnRefusal(name: String): String =
    s"under column policies, qualify a column of a table named $name by the table's alias, " +
      s"not by its schema or catalog ($name.col or a.col)"

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)
  private def lower(s: String): String   = s.toLowerCase(Locale.ROOT)

  /** The bare names (lowercased) the statement reads in more than one catalog or schema. */
  def clashing(tables: List[(Table, (String, String, String))]): Set[String] =
    tables
      .groupBy { case (_, (_, _, tab)) => lower(tab) }
      .collect {
        case (name, refs)
            if refs.map { case (_, (c, s, _)) => (lower(c), lower(s)) }.distinct.size > 1 =>
          name
      }
      .toSet

  /** Renames, in `stmt` itself, every table of `tables` whose bare name is in `names`, then prints
    * it. `tables` are the statement's own nodes, each with where its columns are read.
    */
  def separate(
      stmt: Select,
      tables: List[(Table, (String, String, String))],
      names: Set[String]
  ): Either[String, Separation] =
    sameFromClause(stmt, names).map(n => Left(sameFromClauseRefusal(n))).getOrElse {
      qualifiedColumn(stmt, names).map(n => Left(qualifiedColumnRefusal(n))).getOrElse {
        val marker = "qod_same_" + java.util.UUID.randomUUID().toString.replace("-", "").take(12)
        val seen   = new java.util.IdentityHashMap[Table, String]()
        val keys   = scala.collection.mutable.ListBuffer.empty[(String, (String, String, String))]
        val map    = scala.collection.mutable.Map.empty[String, Renamed]
        tables.foreach { case (t, coords @ (_, _, tab)) =>
          if names.contains(lower(tab)) && !seen.containsKey(t) then
            val key     = s"${marker}_${seen.size + 1}"
            val parts   = t.getNameParts.asScala.toList
            val rawBare = t.getName
            val added   = t.getAlias == null
            seen.put(t, key)
            // Name parts are held table first (JSqlParser's Table), so the written name reverses them.
            val written = parts.reverse.map(p => Option(p).getOrElse("")).mkString(".")
            val binding = Option(t.getAlias).map(_.getName).getOrElse(rawBare)
            map(key) = Renamed(parts, rawBare, unquote(rawBare), written, binding, added)
            keys += (key -> coords)
            t.setNameParts(new java.util.ArrayList(java.util.List.of(key)))
            if added then t.setAlias(new Alias(rawBare))
        }
        val builder = new java.lang.StringBuilder()
        stmt.accept(new net.sf.jsqlparser.util.deparser.StatementDeParser(builder))
        val printed = builder.toString
        // A table the walk did not rename still carries a clashing name: its columns would be
        // read from whichever list that name meets, so the statement is not rewritten.
        val missed = Try(ai.starlake.sql.JSqlStatements.parseFirst(printed)).toOption.collect {
          case sel: Select =>
            Try(ai.starlake.acl.parser.TableExtractor.extract(sel).tables).toOption
        }.flatten
        missed match
          case Some(left) if !left.exists(t => names.contains(lower(unquote(t.getName)))) =>
            Right(new Separation(printed, keys.toList, marker, map.toMap))
          case _ => Left(Unrestorable)
      }
    }

  /** The first clashing name bound twice in one FROM clause: two such tables with no alias, or one
    * and an alias of that name.
    */
  private def sameFromClause(stmt: Select, names: Set[String]): Option[String] =
    def bindings(item: Any): List[String] =
      item match
        case t: Table =>
          Option(t.getAlias)
            .map(a => unquote(a.getName))
            .orElse(Option(t.getName).map(unquote))
            .map(lower)
            .toList
        case pfi: ParenthesedFromItem =>
          bindings(pfi.getFromItem) ++
            Option(pfi.getJoins).toList.flatMap(_.asScala).flatMap(j => bindings(j.getFromItem))
        case fi: FromItem => Option(fi.getAlias).map(a => lower(unquote(a.getName))).toList
        case _            => Nil
    StarExpansion
      .plainSelects(stmt)
      .iterator
      .flatMap { ps =>
        val from = bindings(ps.getFromItem) ++
          Option(ps.getJoins).toList.flatMap(_.asScala).flatMap(j => bindings(j.getFromItem))
        from.groupBy(identity).collectFirst {
          case (name, hits) if hits.size > 1 && names.contains(name) => name
        }
      }
      .nextOption()

  /** The first clashing name a column reference or a star qualifies by its schema or catalog. */
  private def qualifiedColumn(stmt: Select, names: Set[String]): Option[String] =
    def qualified(t: Table): Option[String] =
      Option(t)
        .filter(_.getNameParts.size > 1)
        .flatMap(q => Option(q.getName))
        .map(n => lower(unquote(n)))
    StarExpansion
      .nodes(stmt)
      .iterator
      .flatMap {
        case c: Column          => qualified(c.getTable)
        case s: AllTableColumns => qualified(s.getTable)
        case _                  => None
      }
      .find(names.contains)
