package ai.starlake.quack.edge.policy

import ai.starlake.acl.parser.SqlParser
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.util.TablesNamesFinder

import scala.util.Try

/** Carries DuckLake time-travel clauses (`AT (VERSION => n)`, `AT (TIMESTAMP => expr)`) through the
  * column and row policy rewriters, which parse with jsqlparser.
  *
  * jsqlparser has no `AT (VERSION => n)`, and it takes `AT (TIMESTAMP => ...)` only BEFORE an alias
  * while DuckDB takes it only AFTER one (`FROM t x AT (...)`). Parsing the raw text therefore fails
  * and the router denies fail-closed: a principal holding any column or row policy could not read
  * at a snapshot. Stripping the clause alone, as the ACL validator does, would admit the read but
  * drop the snapshot pin, so the rewritten statement would read the current data instead.
  *
  * [[carry]] strips with the validator's own scanner ([[SqlParser.timeTravelClauses]]), parses the
  * stripped text, and pins each clause to the table reference it followed by source position, as a
  * numbered placeholder in jsqlparser's own time-travel slot on that [[Table]]. The rewriters then
  * work on an ordinary statement whose table nodes carry the placeholders along: the row policy
  * rewriter moves it onto the base table inside its derived table, never onto the wrapper.
  * [[restore]] swaps every placeholder back for the caller's clause, rendered after the alias as
  * DuckDB expects.
  *
  * The clause goes back verbatim and neither rewriter looks inside it. That is safe only because
  * DuckDB binds an AT clause as a constant: it refuses subqueries and column names there ("AT
  * clause cannot contain subqueries", pinned against the bundled DuckDB by
  * `TimeTravelCarrierSpec`), so a clause cannot read a masked column or a filtered row to choose
  * the snapshot it selects.
  *
  * Fails closed at both ends: a clause that does not sit right after exactly one table reference,
  * and a rewritten statement in which any placeholder is missing or left over, deny the statement
  * rather than forward it unpinned or with a placeholder in it.
  */
object TimeTravelCarrier:

  /** What [[carry]] made of a statement. */
  enum Carry:
    /** No time-travel clause: the rewriter handles the text as before. */
    case Absent

    /** `sql` holds a placeholder on each pinned table; `clauses(k)` is the clause of placeholder k.
      */
    case Carried(sql: String, clauses: Vector[String])

    /** A clause could not be pinned to a table reference: the caller fails closed. */
    case Unplaceable

  private val Marker = "__qod_time_travel_"

  /** A string literal, so the column resolver never tries to bind it. */
  private def placeholder(k: Int): String = s"AT (TIMESTAMP => '$Marker${k}__')"

  private val PlaceholderIndex = (Marker + """(\d+)__""").r

  def carry(sql: String): Carry =
    val (stripped, clauses) = SqlParser.timeTravelClauses(sql)
    if clauses.isEmpty then Carry.Absent
    // The marker is reserved: a caller who writes it could otherwise forge a placeholder.
    else if sql.contains(Marker) then Carry.Unplaceable
    else
      Try(CCJSqlParserUtil.parse(stripped)).toOption.fold(Carry.Unplaceable) { stmt =>
        val offsets = lineOffsets(stripped)
        val ends    = tablesOf(stmt).flatMap(t => endOffset(t, offsets).map(t -> _))
        val pinned  = clauses.zipWithIndex.map { (clause, k) =>
          ends.filter { (_, end) =>
            end <= clause.at && stripped.substring(end, clause.at).forall(_.isWhitespace)
          } match
            case List((t, _)) if t.getTimeTravel == null && t.getTimeTravelStrAfterAlias == null =>
              t.setTimeTravel(placeholder(k)): Unit
              true
            case _ => false
        }
        if pinned.forall(identity) then Carry.Carried(stmt.toString, clauses.map(_.text).toVector)
        else Carry.Unplaceable
      }

  /** Puts every clause back on the table carrying its placeholder, in the rewritten `stmt`. False
    * when a placeholder went missing or survived where the walk cannot reach it: the caller must
    * then fail closed.
    */
  def restore(stmt: Statement, clauses: Vector[String]): Boolean =
    val seen = scala.collection.mutable.Set.empty[Int]
    tablesOf(stmt).foreach { t =>
      Option(t.getTimeTravel).flatMap(PlaceholderIndex.findFirstMatchIn).foreach { m =>
        val k = m.group(1).toInt
        if k < clauses.size then
          t.setTimeTravel(null): Unit
          t.setTimeTravelStrAfterAlias(clauses(k)): Unit
          seen += k
      }
    }
    seen.size == clauses.size && !stmt.toString.contains(Marker)

  /** [[restore]] on rewritten text: None when it does not parse or the restore fails. */
  def restore(sql: String, clauses: Vector[String]): Option[String] =
    Try(CCJSqlParserUtil.parse(sql)).toOption
      .filter(restore(_, clauses))
      .map(_.toString)

  /** Every [[Table]] node reached by jsqlparser's traversal, each once. */
  private def tablesOf(stmt: Statement): List[Table] =
    val found = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[Table, java.lang.Boolean]()
    )
    val order  = List.newBuilder[Table]
    val finder = new TablesNamesFinder[Void]:
      override def visit[S](table: Table, context: S): Void =
        if found.add(table) then order += table
        super.visit(table, context)
    Try(finder.getTables(stmt)): Unit
    order.result()

  /** Offset of the first character of each line, with JavaCC's line breaks (`\n`, `\r\n`, a lone
    * `\r`); columns count UTF-16 units and a tab as one, like `String` indices.
    */
  private def lineOffsets(s: String): IndexedSeq[Int] =
    val starts = IndexedSeq.newBuilder[Int]
    starts += 0
    var i = 0
    while i < s.length do
      s(i) match
        case '\n'                                          => starts += i + 1
        case '\r' if i + 1 >= s.length || s(i + 1) != '\n' => starts += i + 1
        case _                                             => ()
      i += 1
    starts.result()

  /** Offset just past the table reference, its alias included, in the text it was parsed from. */
  private def endOffset(t: Table, lines: IndexedSeq[Int]): Option[Int] =
    Option(t.getASTNode).flatMap(n => Option(n.jjtGetLastToken)).collect {
      case tok if tok.endLine >= 1 && tok.endLine <= lines.size =>
        lines(tok.endLine - 1) + tok.endColumn
    }
