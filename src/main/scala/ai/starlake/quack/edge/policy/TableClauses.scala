package ai.starlake.quack.edge.policy

import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.TableFunction

/** The clauses JSqlParser keeps on a node in table position that a rewrite replacing the node with
  * a derived table would drop. The row-policy rewrite rebuilds a policy table from its name parts
  * (`(SELECT * FROM <name> WHERE ...) <alias>`) and the metadata filter replaces a system-catalog
  * read with one of its own, so anything else the node holds (a time-travel `AT (...)`, a
  * `TABLESAMPLE`, a PIVOT written after the table) silently vanished: the statement then answered
  * as a different one, with no error. Such a read is refused instead, naming the clause, never a
  * value from it. Keeping the clause (time travel through the wrap) is out of scope.
  *
  * Each description is a fixed phrase that fits after "read with".
  */
object TableClauses:

  val TimeTravel    = "a time-travel clause (AT ...)"
  val ForAsOf       = "a time-travel clause DuckDB does not take (FOR ... AS OF)"
  val Sample        = "a sample clause (TABLESAMPLE, USING SAMPLE)"
  val Pivot         = "a PIVOT or UNPIVOT clause written after the table"
  val Hint          = "a table hint (USE INDEX, WITH (...))"
  val TableVariable = "a table variable"
  val Name          =
    "a name the rewrite cannot print back as written (a database link, more than three parts)"
  val FunctionExtra = "a clause on the table function (LATERAL, WITH ORDINALITY, WITH OFFSET)"
  val Other         = "a clause the rewrite cannot keep"

  /** The clause `t` carries that a rewrite printing it back as `rebuiltName` plus its alias would
    * lose, if any. The fields JSqlParser 5.4 holds are named one by one, so the refusal says what
    * to remove; the last two checks compare renderings, so a field a later JSqlParser adds is
    * refused as [[Other]] rather than dropped.
    */
  def unkept(t: Table, rebuiltName: String): Option[String] =
    val alias = Option(t.getAlias).fold("")(_.toString)
    if t.getTimeTravel != null || t.getTimeTravelStrAfterAlias != null then Some(timeTravelOf(t))
    else if t.getSampleClause != null then Some(Sample)
    else if t.getPivot != null || t.getUnPivot != null then Some(Pivot)
    else if t.getIndexHint != null || t.getSqlServerHints != null then Some(Hint)
    else if t.isTableVariable then Some(TableVariable)
    else if t.getFullyQualifiedName != rebuiltName then Some(Name)
    else if t.toString != rebuiltName + alias then Some(Other)
    else None

  /** Which time-travel spelling `t` carries: DuckDB's `AT (...)`, or one JSqlParser also reads
    * (`FOR TIMESTAMP AS OF`, `FOR SYSTEM_TIME AS OF`) that DuckDB refuses, so the refusal names the
    * one the caller wrote.
    */
  private def timeTravelOf(t: Table): String =
    val text = Option(t.getTimeTravel).orElse(Option(t.getTimeTravelStrAfterAlias)).getOrElse("")
    if text.trim.toUpperCase(java.util.Locale.ROOT).startsWith("AT") then TimeTravel else ForAsOf

  /** The clause `tf` carries that a rewrite replacing the call with a derived table under its alias
    * would lose, if any. As for a table, the last check compares renderings, so a field a later
    * JSqlParser adds is refused as [[Other]] rather than dropped (the pivot fields are named
    * because `TableFunction.toString` does not print them).
    */
  def unkept(tf: TableFunction): Option[String] =
    val alias = Option(tf.getAlias).fold("")(_.toString)
    val call  = Option(tf.getFunction).fold("")(_.toString)
    if tf.getSampleClause != null then Some(Sample)
    else if tf.getPivot != null || tf.getUnPivot != null then Some(Pivot)
    else if tf.getPrefix != null || tf.getWithClause != null || tf.isWithOffset ||
      tf.getOffsetAlias != null
    then Some(FunctionExtra)
    else if tf.toString != call + alias then Some(Other)
    else None
