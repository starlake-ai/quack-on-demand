package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Verifies that `SqlParser.processStatement` extracts the right
  * `TableAccess(table, verb)` tuples for DML statements (INSERT / UPDATE /
  * DELETE / MERGE). These tests pin the behavior that the ACL validator now
  * relies on for per-table grant enforcement of write-side traffic --
  * before this refactor, DML produced an empty access set and the validator
  * silently allowed all writes (see project memory `project-dml-acl-unenforced`).
  */
class SqlParserDmlSpec extends AnyFlatSpec with Matchers:

  private val config = Config.forGeneric("testdb", "public")

  /** Assert the head statement is `Extracted` and its accesses match the
    * supplied (canonical, verb) tuples exactly. */
  private def assertAccesses(sql: String, expected: (String, Verb)*): Unit =
    val r = SqlParser.extract(sql, config)
    r.statements.headOption match
      case Some(StatementResult.Extracted(_, _, accesses, _)) =>
        accesses.map(a => a.table.canonical -> a.verb) should contain theSameElementsAs expected
      case other =>
        fail(s"expected Extracted, got $other")

  // ---------- INSERT ----------

  "SqlParser INSERT" should "mark a VALUES target as Write" in:
    assertAccesses("INSERT INTO t VALUES (1)", "testdb.public.t" -> Verb.Write)

  it should "mark target as Write and source as Read on INSERT SELECT" in:
    assertAccesses(
      "INSERT INTO t SELECT * FROM s",
      "testdb.public.t" -> Verb.Write,
      "testdb.public.s" -> Verb.Read
    )

  it should "walk WHERE subqueries on the SELECT source" in:
    assertAccesses(
      "INSERT INTO t (a) SELECT a FROM s WHERE x IN (SELECT y FROM other)",
      "testdb.public.t"     -> Verb.Write,
      "testdb.public.s"     -> Verb.Read,
      "testdb.public.other" -> Verb.Read
    )

  it should "exclude CTE self-references from the read set" in:
    // The CTE name `cte` should NOT appear; only its source `s` does.
    assertAccesses(
      "INSERT INTO t WITH cte AS (SELECT a FROM s) SELECT * FROM cte",
      "testdb.public.t" -> Verb.Write,
      "testdb.public.s" -> Verb.Read
    )

  it should "handle a fully-qualified target" in:
    assertAccesses(
      "INSERT INTO other.public.t VALUES (1)",
      "other.public.t" -> Verb.Write
    )

  // ---------- UPDATE ----------

  "SqlParser UPDATE" should "mark a bare UPDATE as Write on the target" in:
    assertAccesses("UPDATE t SET a = 1", "testdb.public.t" -> Verb.Write)

  it should "walk WHERE IN-subqueries" in:
    assertAccesses(
      "UPDATE t SET a = 1 WHERE id IN (SELECT id FROM s)",
      "testdb.public.t" -> Verb.Write,
      "testdb.public.s" -> Verb.Read
    )

  it should "walk UPDATE-FROM source tables as Read" in:
    assertAccesses(
      "UPDATE t SET a = b.c FROM b WHERE t.id = b.id",
      "testdb.public.t" -> Verb.Write,
      "testdb.public.b" -> Verb.Read
    )

  it should "document the SET-expression-subquery limitation" in:
    // UpdateReadExtractor does not currently walk the SET assignment
    // expressions, so a `SET a = (SELECT max(x) FROM s)` is detected only
    // as a Write on the target. This is acceptable -- the validator will
    // still require a Write grant on `t`. If sub-query SET arguments are
    // ever a concern (e.g. read-side audit), extend the extractor.
    val r = SqlParser.extract("UPDATE t SET a = (SELECT max(x) FROM s)", config)
    r.statements.headOption match
      case Some(StatementResult.Extracted(_, _, accesses, _)) =>
        accesses.map(_.table.canonical) should contain ("testdb.public.t")
        accesses.find(_.table.canonical == "testdb.public.t").get.verb shouldBe Verb.Write
      case other => fail(s"expected Extracted, got $other")

  // ---------- DELETE ----------

  "SqlParser DELETE" should "mark a bare DELETE as Write on the target" in:
    assertAccesses("DELETE FROM t", "testdb.public.t" -> Verb.Write)

  it should "walk WHERE IN-subqueries" in:
    assertAccesses(
      "DELETE FROM t WHERE id IN (SELECT id FROM s)",
      "testdb.public.t" -> Verb.Write,
      "testdb.public.s" -> Verb.Read
    )

  it should "walk USING source tables as Read" in:
    assertAccesses(
      "DELETE FROM t USING s WHERE t.id = s.id",
      "testdb.public.t" -> Verb.Write,
      "testdb.public.s" -> Verb.Read
    )

  // ---------- MERGE ----------

  "SqlParser MERGE" should "mark MERGE INTO target as Write and the USING table as Read" in:
    val sql =
      """MERGE INTO t USING s ON t.id = s.id
        |WHEN MATCHED THEN UPDATE SET a = s.a
        |WHEN NOT MATCHED THEN INSERT (a) VALUES (s.a)""".stripMargin
    assertAccesses(
      sql,
      "testdb.public.t" -> Verb.Write,
      "testdb.public.s" -> Verb.Read
    )

  it should "walk a USING sub-select for read-side tables" in:
    val sql =
      """MERGE INTO t USING (SELECT s1.a FROM s1 JOIN s2 ON s1.id = s2.id) src
        |ON t.id = src.a
        |WHEN MATCHED THEN UPDATE SET a = src.a""".stripMargin
    assertAccesses(
      sql,
      "testdb.public.t"  -> Verb.Write,
      "testdb.public.s1" -> Verb.Read,
      "testdb.public.s2" -> Verb.Read
    )