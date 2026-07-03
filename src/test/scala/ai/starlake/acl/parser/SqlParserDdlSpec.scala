package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Verifies that `SqlParser.processStatement` extracts the right
  * `TableAccess(table, verb)` tuples for DDL statements (CREATE / DROP /
  * ALTER / TRUNCATE). Mirrors `SqlParserDmlSpec` for the write path -- DDL
  * grants are now enforceable per-table because the parser emits an
  * explicit `Verb.Ddl` access on the target. */
class SqlParserDdlSpec extends AnyFlatSpec with Matchers:

  private val config = Config.forGeneric("testdb", "public")

  private def headExtracted(sql: String): Set[(String, Verb)] =
    SqlParser.extract(sql, config).statements.headOption match
      case Some(StatementResult.Extracted(_, _, accesses, _, _)) =>
        accesses.map(a => a.table.canonical -> a.verb)
      case other =>
        fail(s"expected Extracted, got $other")

  // ---------- CREATE TABLE ----------

  "SqlParser CREATE TABLE" should "mark a plain table create as Ddl on the target" in:
    headExtracted("CREATE TABLE t (a INT)") shouldBe Set("testdb.public.t" -> Verb.Ddl)

  it should "split CTAS into Ddl(target) + Read(source)" in:
    headExtracted("CREATE TABLE t AS SELECT * FROM s") shouldBe Set(
      "testdb.public.t" -> Verb.Ddl,
      "testdb.public.s" -> Verb.Read
    )

  // ---------- CREATE VIEW ----------

  "SqlParser CREATE VIEW" should "mark the view as Ddl and walk the source" in:
    headExtracted("CREATE OR REPLACE VIEW v AS SELECT * FROM s") shouldBe Set(
      "testdb.public.v" -> Verb.Ddl,
      "testdb.public.s" -> Verb.Read
    )

  it should "walk JOIN sources in the view body" in:
    headExtracted("CREATE VIEW v AS SELECT * FROM s JOIN s2 ON s.id = s2.id") shouldBe Set(
      "testdb.public.v"  -> Verb.Ddl,
      "testdb.public.s"  -> Verb.Read,
      "testdb.public.s2" -> Verb.Read
    )

  // ---------- DROP ----------

  "SqlParser DROP TABLE" should "mark the dropped table as Ddl" in:
    headExtracted("DROP TABLE t") shouldBe Set("testdb.public.t" -> Verb.Ddl)

  it should "treat DROP SCHEMA's schema-name as a Ddl target (JSQLParser limitation)" in:
    // JSQLParser's Drop AST exposes the dropped name via `getName(): Table`,
    // even for `DROP SCHEMA myschema` where the name is logically a schema
    // identifier. The parser arm has no signal to distinguish a schema
    // from a table at this layer, so the qualified `(testdb, public,
    // myschema)` triple ends up in the access set as a Ddl. Operators who
    // want to enforce schema-level DDL grants can do so by granting Ddl on
    // `*.<schema>.*` and `*.*.*` patterns; the validator already matches
    // wildcards. Documented so a future reader doesn't try to "fix" this
    // by routing DROP SCHEMA to ControlFlow -- that would silently allow
    // unprivileged schema drops.
    headExtracted("DROP SCHEMA myschema") shouldBe Set("testdb.public.myschema" -> Verb.Ddl)

  // ---------- ALTER ----------

  "SqlParser ALTER TABLE" should "mark a plain column-add as Ddl on the target" in:
    headExtracted("ALTER TABLE t ADD COLUMN x INT") shouldBe Set("testdb.public.t" -> Verb.Ddl)

  it should "still mark target as Ddl when adding an FK constraint" in:
    // We deliberately do NOT walk the FOREIGN KEY ... REFERENCES other
    // target into Read or Ddl: the validator requires the operator to
    // hold a Ddl grant on `t` anyway, and adding the referenced table to
    // the access set risks surprising denials for cross-table FKs.
    // Documented for visibility.
    headExtracted("ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (x) REFERENCES other (id)") shouldBe
      Set("testdb.public.t" -> Verb.Ddl)

  // ---------- TRUNCATE ----------

  "SqlParser TRUNCATE" should "mark the target as Write (mass-delete semantics)" in:
    headExtracted("TRUNCATE TABLE t") shouldBe Set("testdb.public.t" -> Verb.Write)