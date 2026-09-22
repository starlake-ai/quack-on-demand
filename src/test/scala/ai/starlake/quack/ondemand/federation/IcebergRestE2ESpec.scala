package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.ondemand.federation.iceberg.{IcebergAuthType, IcebergRestConfig}

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End to end against a live `apache/iceberg-rest-fixture`, driving the real duckdb CLI with the
  * SQL `IcebergSetupSql.render` actually emits. No ATTACH here is hand written: every case runs
  * whatever the renderer produces today, so a renderer that stopped emitting a needed option takes
  * this whole spec down rather than leaving it asserting against a stale copy.
  *
  * Proves: the rendered attach connects to a live REST catalog, and DuckDB's Iceberg write surface
  * (INSERT / UPDATE / DELETE / MERGE / ALTER / DROP / time travel) works through it. Also proves
  * the one thing Task 6 could not: that the `READ_ONLY` the renderer emits for a read-only source
  * is honoured by the iceberg extension against a live catalog, not merely accepted as a recognized
  * option.
  *
  * Does NOT boot a manager, so it proves nothing about the FlightSQL edge. Edge enforcement lives
  * in `CatalogWriteScreenSpec` and the router specs, and a green run here is not full-stack proof.
  *
  * The fixture is unauthenticated, so this covers `authType = none` only. The oauth2 / token /
  * sigv4 renderings are covered by `IcebergSetupSqlSpec`; certifying a real OAuth2 catalog
  * (Polaris, Lakekeeper) is follow-up work.
  *
  * Cancelled, not failed, when the fixture or the duckdb CLI is absent. A cancelled run asserts
  * nothing at all, so read the cancellation count before reading a green bar.
  */
class IcebergRestE2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val alias  = "ice"
  private val schema = "e2e_" + java.lang.Long.toHexString(System.nanoTime())

  private val config = IcebergRestConfig(
    uri = IcebergFixture.endpoint,
    warehouse = IcebergFixture.warehouse,
    authType = Some(IcebergAuthType.NoAuth)
  )

  private lazy val rw: String = IcebergFixture.attachBlock(config, alias, readOnly = false)
  private lazy val ro: String = IcebergFixture.attachBlock(config, alias, readOnly = true)

  private def available: Boolean = IcebergFixture.duckdbPresent && IcebergFixture.reachable

  private def requireFixture(): Unit =
    assume(IcebergFixture.duckdbPresent, IcebergFixture.missingDuckdb)
    assume(IcebergFixture.reachable, IcebergFixture.missingFixture)

  /** Run `body` on a writable attach of the live catalog. */
  private def run(body: String*): DuckdbRun =
    IcebergFixture.duckdb(IcebergFixture.script(IcebergFixture.storagePrelude +: rw +: body*))

  /** Run `body` on a READ-ONLY attach of the same catalog. */
  private def runReadOnly(body: String*): DuckdbRun =
    IcebergFixture.duckdb(IcebergFixture.script(IcebergFixture.storagePrelude +: ro +: body*))

  override def beforeAll(): Unit =
    if available then
      val r = run(s"""CREATE SCHEMA IF NOT EXISTS "$alias"."$schema";""")
      assert(r.code == 0, s"schema create failed: ${r.clue}")

  /** Drop the tables, then the schema. `DROP SCHEMA ... CASCADE` looks like the obvious cleanup and
    * is a silent no-op here: DuckDB v1.5.4 answers "Not implemented Error: DROP SCHEMA
    * <schema_name> CASCADE is not supported for Iceberg schemas currently", and an unchecked
    * teardown would have left a schema behind on every run without anyone noticing. So the table
    * list is read back from the catalog rather than assumed, and the result is asserted.
    */
  override def afterAll(): Unit =
    if available then
      val listed = run(
        s"SELECT table_name FROM duckdb_tables() " +
          s"WHERE database_name = '$alias' AND schema_name = '$schema';"
      )
      val drops = listed.values.map(t => s"""DROP TABLE "$alias"."$schema"."$t";""")
      val r     = run(drops :+ s"""DROP SCHEMA "$alias"."$schema";"""*)
      assert(r.code == 0, s"teardown of $schema failed: ${r.clue}")

  private def table(name: String) = s""""$alias"."$schema"."$name""""

  private def tableCount(name: String) =
    s"SELECT 'count_$name=' || count(*) FROM duckdb_tables() " +
      s"WHERE database_name = '$alias' AND schema_name = '$schema' AND table_name = '$name';"

  "the rendered attach" should "attach the live catalog under its alias" in {
    requireFixture()
    val r = run(s"SELECT database_name FROM duckdb_databases() WHERE database_name = '$alias';")
    withClue(r.clue) {
      r.code shouldBe 0
      // The alias itself, not merely "some database exists": a renderer that dropped the AS clause
      // or attached under a derived name would still leave duckdb_databases() non-empty.
      r.values shouldBe List(alias)
    }
  }

  it should "create a table and insert rows" in {
    requireFixture()
    val t = table("t_insert")
    val r = run(
      s"CREATE TABLE $t (id INTEGER, name VARCHAR);",
      s"INSERT INTO $t VALUES (1, 'a'), (2, 'b');",
      s"SELECT string_agg(id || ':' || name, ',' ORDER BY id) FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      r.values shouldBe List("1:a,2:b")
    }
  }

  it should "update rows, leaving the untouched row alone" in {
    requireFixture()
    val t = table("t_update")
    val r = run(
      s"CREATE TABLE $t (id INTEGER, name VARCHAR);",
      s"INSERT INTO $t VALUES (1, 'a'), (2, 'b');",
      s"UPDATE $t SET name = 'z' WHERE id = 1;",
      s"SELECT string_agg(id || ':' || name, ',' ORDER BY id) FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // Both rows, in order: an UPDATE that rewrote every row would also satisfy "contains z".
      r.values shouldBe List("1:z,2:b")
    }
  }

  it should "delete exactly the matching row" in {
    requireFixture()
    val t = table("t_delete")
    val r = run(
      s"CREATE TABLE $t (id INTEGER);",
      s"INSERT INTO $t VALUES (1), (2), (3);",
      s"DELETE FROM $t WHERE id = 2;",
      s"SELECT string_agg(id::VARCHAR, ',' ORDER BY id) FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // Which rows survived, not how many: a DELETE that dropped the wrong row also leaves 2.
      r.values shouldBe List("1,3")
    }
  }

  it should "merge, taking the matched and the unmatched branch" in {
    requireFixture()
    val t = table("t_merge")
    val s = table("t_merge_src")
    val r = run(
      s"CREATE TABLE $t (id INTEGER, v VARCHAR);",
      s"INSERT INTO $t VALUES (1, 'old');",
      s"CREATE TABLE $s (id INTEGER, v VARCHAR);",
      s"INSERT INTO $s VALUES (1, 'new'), (2, 'added');",
      s"MERGE INTO $t tgt USING $s src ON tgt.id = src.id " +
        s"WHEN MATCHED THEN UPDATE SET v = src.v " +
        s"WHEN NOT MATCHED THEN INSERT VALUES (src.id, src.v);",
      s"SELECT string_agg(id || '=' || v, ',' ORDER BY id) FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // id 1 updated in place (not duplicated) and id 2 inserted: one assertion covering both arms.
      r.values shouldBe List("1=new,2=added")
    }
  }

  it should "alter a table and accept a row using the new column" in {
    requireFixture()
    val t = table("t_alter")
    val r = run(
      s"CREATE TABLE $t (id INTEGER);",
      s"ALTER TABLE $t ADD COLUMN extra VARCHAR;",
      s"INSERT INTO $t VALUES (1, 'x');",
      s"SELECT id || '/' || extra FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      r.values shouldBe List("1/x")
    }
  }

  it should "drop a table out of the catalog" in {
    requireFixture()
    val t = table("t_drop")
    val r = run(
      s"CREATE TABLE $t (id INTEGER);",
      tableCount("t_drop"),
      s"DROP TABLE $t;",
      tableCount("t_drop")
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // The leading 1 is what keeps the trailing 0 meaningful: a lookup that matched nothing either
      // way (wrong schema name, wrong catalog) would report 0 without the DROP having done
      // anything at all.
      r.values shouldBe List("count_t_drop=1", "count_t_drop=0")
    }
  }

  it should "read an earlier snapshot by id" in {
    requireFixture()
    val t         = table("t_tt")
    val qualified = s"$alias.$schema.t_tt"
    val setup     = run(
      s"CREATE TABLE $t (id INTEGER);",
      s"INSERT INTO $t VALUES (1);",
      s"INSERT INTO $t VALUES (2);",
      s"SELECT snapshot_id FROM iceberg_snapshots('$qualified') ORDER BY sequence_number LIMIT 1;"
    )
    withClue(setup.clue)(setup.code shouldBe 0)
    val first = setup.values match
      case one :: Nil => one
      case other      => fail(s"expected one snapshot id, got $other in: ${setup.clue}")

    val r = run(
      s"SELECT 'at_first=' || count(*) FROM $t AT (VERSION => $first);",
      s"SELECT 'latest=' || count(*) FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // Real time travel: the table genuinely held one row at that snapshot and holds two now. A
      // case that only counted rows in iceberg_snapshots() would pass on a catalog that never
      // recorded the second insert at all.
      r.values shouldBe List("at_first=1", "latest=2")
    }
  }

  it should "create and drop a schema in the live catalog" in {
    requireFixture()
    // A schema of this case's own, so the spec's own `$schema` (created in beforeAll, dropped in
    // afterAll) is never the thing under test here.
    val own     = schema + "_sc"
    val present = (label: String) =>
      s"SELECT '$label=' || count(*) FROM duckdb_schemas() " +
        s"WHERE database_name = '$alias' AND schema_name = '$own';"
    val r = run(
      s"""CREATE SCHEMA "$alias"."$own";""",
      present("created"),
      s"""DROP SCHEMA "$alias"."$own";""",
      present("dropped")
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // The leading 1 is what keeps the trailing 0 meaningful: a lookup that matched nothing
      // either way (wrong catalog, wrong column) would report 0 without either statement having
      // done anything. Note `DROP SCHEMA ... CASCADE` is NOT what is asserted -- DuckDB v1.5.4
      // answers "Not implemented Error: DROP SCHEMA <schema_name> CASCADE is not supported for
      // Iceberg schemas currently" and, over stdin, carries on -- so this case drops an EMPTY
      // schema, which is the shape that works. See afterAll for the same finding.
      r.values shouldBe List("created=1", "dropped=0")
    }
  }

  it should "read an earlier snapshot by timestamp" in {
    requireFixture()
    val t         = table("t_tt_ts")
    val qualified = s"$alias.$schema.t_tt_ts"
    val setup     = run(
      s"CREATE TABLE $t (id INTEGER);",
      s"INSERT INTO $t VALUES (1);",
      s"INSERT INTO $t VALUES (2);",
      s"SELECT timestamp_ms FROM iceberg_snapshots('$qualified') " +
        s"ORDER BY sequence_number LIMIT 1;"
    )
    withClue(setup.clue)(setup.code shouldBe 0)
    // The catalog's OWN recorded commit time for the first snapshot, not a clock read in this
    // process: a midpoint computed here would depend on the fixture's clock agreeing with the
    // test host's, and on the two inserts being far enough apart to have a midpoint at all.
    val firstAt = setup.values match
      case one :: Nil => one
      case other      => fail(s"expected one snapshot timestamp, got $other in: ${setup.clue}")

    val r = run(
      s"SELECT 'at_first=' || count(*) FROM $t AT (TIMESTAMP => TIMESTAMP '$firstAt');",
      s"SELECT 'latest=' || count(*) FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // Real time travel by timestamp, distinct from the by-id case above: the table held one row
      // as of the first commit's timestamp and holds two now.
      r.values shouldBe List("at_first=1", "latest=2")
    }
  }

  it should "map Iceberg column types back to the DuckDB types they were written as" in {
    requireFixture()
    val t = table("t_types")
    // Written and read back in SEPARATE duckdb sessions on purpose. In the writing session the
    // types are simply what CREATE TABLE said; only a fresh ATTACH forces DuckDB to derive them
    // from the Iceberg table metadata the catalog stored, which is the mapping under test.
    val setup = run(
      s"CREATE TABLE $t (" +
        "b BOOLEAN, i INTEGER, l BIGINT, f FLOAT, d DOUBLE, n DECIMAL(10,2), " +
        "s VARCHAR, dt DATE, ts TIMESTAMP, bin BLOB);",
      s"INSERT INTO $t VALUES (true, 42, 9000000000, 1.5, 2.5, 3.25, 'x', " +
        "DATE '2026-01-02', TIMESTAMP '2026-01-02 03:04:05', 'ab'::BLOB);"
    )
    withClue(setup.clue)(setup.code shouldBe 0)

    val r = run(
      s"SELECT typeof(b) || ',' || typeof(i) || ',' || typeof(l) || ',' || typeof(f) || ',' || " +
        s"typeof(d) || ',' || typeof(n) || ',' || typeof(s) || ',' || typeof(dt) || ',' || " +
        s"typeof(ts) || ',' || typeof(bin) FROM $t;",
      s"SELECT b || '|' || i || '|' || l || '|' || f || '|' || d || '|' || n || '|' || s || " +
        s"'|' || dt || '|' || ts || '|' || bin FROM $t;"
    )
    withClue(r.clue) {
      r.code shouldBe 0
      // `typeof` on the scan, not `duckdb_columns()`: on a fresh attach `duckdb_columns()` reports
      // a single `__` column of type UNKNOWN for an Iceberg table nothing has read yet, which is a
      // lazy placeholder rather than the mapping, and a case asserting against it would have been
      // asserting on DuckDB's catalog laziness.
      //
      // Both lines matter. The types alone would pass if every value came back NULL; the values
      // alone would pass if DECIMAL(10,2) had degenerated to DOUBLE, since 3.25 prints the same.
      r.values shouldBe List(
        "BOOLEAN,INTEGER,BIGINT,FLOAT,DOUBLE,DECIMAL(10,2),VARCHAR,DATE,TIMESTAMP,BLOB",
        "true|42|9000000000|1.5|2.5|3.25|x|2026-01-02|2026-01-02 03:04:05|ab"
      )
    }
  }

  "a read-only rendered attach" should "still serve reads" in {
    requireFixture()
    val t     = table("t_ro_read")
    val setup = run(s"CREATE TABLE $t (id INTEGER);", s"INSERT INTO $t VALUES (1), (2);")
    withClue(setup.clue)(setup.code shouldBe 0)

    val r = runReadOnly(s"SELECT count(*) FROM $t;")
    withClue(r.clue) {
      r.code shouldBe 0
      r.values shouldBe List("2")
    }
  }

  /** The Task 6 MUST VERIFY. Before this, `READ_ONLY` was known only to be a recognized Iceberg
    * ATTACH option (a bogus option fails ATTACH with "Unhandled options found"; this one does not)
    * and to be enforced for a FILE-BACKED attach. Whether the iceberg extension honoured it against
    * a live REST catalog was unproven, which is why `IcebergSetupSql.render`'s scaladoc called it
    * the INTENDED rather than the confirmed enforcement. It does: every write verb below is refused
    * by the engine, above the extension and below SQL parsing.
    *
    * All five verbs are issued in one session, one statement per line so the CLI runs past each
    * refusal (see `IcebergFixture`), and each refusal is named. A case asserting only that SOME
    * error mentioned read-only would pass with four of the five going through. The trailing row
    * count rules out a refusal reported after the catalog had already been mutated.
    */
  it should "have DuckDB refuse every write verb at the engine layer" in {
    requireFixture()
    val t     = table("t_ro_write")
    val setup = run(s"CREATE TABLE $t (id INTEGER);", s"INSERT INTO $t VALUES (1), (2);")
    withClue(setup.clue)(setup.code shouldBe 0)

    val r = runReadOnly(
      s"SELECT 'before=' || count(*) FROM $t;",
      s"INSERT INTO $t VALUES (99);",
      s"UPDATE $t SET id = 5 WHERE id = 1;",
      s"DELETE FROM $t WHERE id = 1;",
      s"CREATE TABLE ${table("t_ro_created")} (id INTEGER);",
      s"DROP TABLE $t;",
      s"SELECT 'after=' || count(*) FROM $t;"
    )
    withClue(r.clue) {
      r.code should not be 0
      val refused = r.errors
        .filter(_.contains("which is attached in read-only mode"))
        .flatMap(l => IcebergRestE2ESpec.StatementType.findFirstMatchIn(l).map(_.group(1)))
      refused shouldBe List("INSERT", "UPDATE", "DELETE", "CREATE", "DROP")
      // And the refusals were real: the table still holds its two rows and still exists.
      r.values shouldBe List("before=2", "after=2")
    }
  }

object IcebergRestE2ESpec:
  /** Pulls the verb out of `Cannot execute statement of type "INSERT" on database ...`. */
  private val StatementType = """of type "(\w+)"""".r
