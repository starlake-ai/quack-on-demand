package ai.starlake.quack.edge.search

import ai.starlake.quack.edge.sql.FunctionCalls

/** The search functions of the search spec (section 4.1) and the one detector of a statement that
  * calls one, read from DuckDB's own tokens like every other call screen ([[FunctionCalls]]).
  *
  * The detector over-includes, and so fails closed: a name followed by `(` anywhere reads as a
  * call, inside a string literal (FunctionCalls scans literal bodies), as a CTE name with a column
  * list (`WITH qod_search(a) AS ...`) or as an alias with a column list (`FROM t AS
  * qod_search(a)`), and a scan cut short at its nesting bound counts as a call. Its consumers: the
  * row-policy wrap of spec 4.2 rule 3, which then wraps every policy table as `SELECT *, rowid`
  * (where the table has a virtual `rowid` this adds one column; where it has none, a view or some
  * federated tables, the statement no longer binds for the row-policy holder, a refusal and never a
  * leak); the column-policy step, which denies every flagged statement of a column-policy holder
  * (spec 4.3); and [[SearchStarScreen]], which refuses a flagged statement's star projections for
  * every principal (spec 4.2 rule 10).
  */
private[quack] object SearchFunctions:

  val Names: Set[String] = Set("qod_search", "qod_search_matches", "qod_knn", "qod_hybrid")

  def callsSearch(sql: String): Boolean =
    val scan = FunctionCalls.scan(sql)
    scan.truncated || scan.calls.exists(c => Names.contains(c.name))
