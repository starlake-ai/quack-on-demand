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
  *
  * [[Names]] also holds the Phase 0 prototype's drain probes ([[DrainProbes]], bench only, gone in
  * Phase 1): they take an input of rowids like a search function and answer its count and maximum,
  * so the column-policy denial must apply to them too, or a column-policy holder's probe over a
  * masked BIGINT column would answer from the unmasked values.
  */
private[quack] object SearchFunctions:

  /** The prototype's drain probes of plan Tasks 5 and 7a (qod-node's search_functions.cpp). */
  val DrainProbes: Set[String] =
    Set("qod_search_drain", "qod_search_drain_parallel", "qod_search_drain_sink")

  val Names: Set[String] =
    Set("qod_search", "qod_search_matches", "qod_knn", "qod_hybrid") ++ DrainProbes

  def callsSearch(sql: String): Boolean =
    val scan = FunctionCalls.scan(sql)
    scan.truncated || scan.calls.exists(c => Names.contains(c.name))

  /** The prototype's index id (search spec 6.2, revision 5; no index registry until Phase 1): the
    * first 16 bytes of SHA-256(`qod-search-prototype|v1|` + table + `|` + column) with the RFC 9562
    * version 8 nibble and variant bits, as canonical lowercase hyphenated text. The Scala twin of
    * qod-node's `PrototypeIndexId` (search_lineage.cpp); both are pinned by
    * native/qod-node/tests/golden/search-index-id-v1.txt. `table` is `catalog.schema.table` in
    * lowercase, `column` the column as the table names it.
    */
  def prototypeIndexId(table: String, column: String): String =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(
        s"qod-search-prototype|v1|$table|$column".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    digest(6) = ((digest(6) & 0x0f) | 0x80).toByte
    digest(8) = ((digest(8) & 0x3f) | 0x80).toByte
    val hex = digest.take(16).map(b => f"${b & 0xff}%02x").mkString
    s"${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
      s"${hex.substring(16, 20)}-${hex.substring(20, 32)}"
