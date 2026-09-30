package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.ondemand.catalog.iceberg.IcebergCatalogSql.SnapshotFilter
import ai.starlake.quack.ondemand.catalog.iceberg.{
  IcebergCatalogSql,
  IcebergSnapshot,
  IcebergSnapshots
}
import ai.starlake.quack.ondemand.federation.iceberg.{IcebergAuthType, IcebergRestConfig}

import io.circe.{parser, Json}

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** End to end for the read-only Iceberg catalog view builders (`IcebergCatalogSql`, Task 2) and the
  * snapshot parser (`IcebergSnapshots`, Task 1), driving the real duckdb CLI against a live
  * `apache/iceberg-rest-fixture` catalog. Nothing here is wired into the manager yet: this pins the
  * SQL and the parser to real engine behaviour before the REST handlers (later tasks) exist.
  *
  * One table, `t (id INTEGER, v VARCHAR)` in a schema unique to this run, carries one mutation
  * sequence through the whole spec: append 3 rows, append 1 more, `UPDATE` one row, `DELETE`
  * another. That is 4 committed snapshots (append, append, overwrite, delete, oldest first) which
  * every case below reads a different slice of, so the cases share one `beforeAll` fixture rather
  * than each building its own table the way `IcebergRestE2ESpec` does - the views under test are
  * inherently about history, and history only exists once something has happened to a table.
  *
  * JSON, not `-list`: `IcebergSnapshots.parse` reads BIGINT sequence/timestamp columns and a
  * BOOLEAN `is_current` column as real JSON numbers/booleans (matching what `ArrowRowsDecoder`
  * yields off the wire), which duckdb's `-list` output cannot carry - everything comes back as
  * VARCHAR text in that mode. [[IcebergFixture.duckdb]] takes the CLI flags as a parameter for
  * exactly this: [[runJson]] passes `-json`, and the schema/table teardown below keeps the default
  * `-list -noheader`, which needs no typed columns.
  *
  * Cancelled, not failed, when the fixture or the duckdb CLI is absent (same convention as
  * `IcebergRestE2ESpec`, see `IcebergFixture`).
  */
class IcebergCatalogE2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val alias  = "ice"
  private val schema = "qod_catalog_e2e_" + java.lang.Long.toHexString(System.nanoTime())
  private val tbl    = "t"

  private val config = IcebergRestConfig(
    uri = IcebergFixture.endpoint,
    warehouse = IcebergFixture.warehouse,
    authType = Some(IcebergAuthType.NoAuth)
  )

  private lazy val rw: String = IcebergFixture.attachBlock(config, alias, readOnly = false)

  private def available: Boolean = IcebergFixture.duckdbPresent && IcebergFixture.reachable

  private def requireFixture(): Unit =
    assume(IcebergFixture.duckdbPresent, IcebergFixture.missingDuckdb)
    assume(IcebergFixture.reachable, IcebergFixture.missingFixture)

  private def table(name: String) = s""""$alias"."$schema"."$name""""

  /** Everything captured in [[beforeAll]], read by every `it should` case below. `None` only when
    * the fixture was unavailable, in which case every case cancels itself via [[requireFixture]]
    * before ever touching it.
    */
  private final case class Captured(
      historyRaw: String,
      history: List[IcebergSnapshot],
      pageJson: Json,
      filesJson: Json,
      schemasJson: Json,
      tablesJson: Json,
      columnsJson: Json,
      atOrBeforeJson: Json,
      preview1Json: Json,
      preview2Json: Json,
      previewCurrentJson: Json,
      diffJson: Json,
      currentJson: Json
  )

  private var captured: Option[Captured] = None

  private def c: Captured = captured.getOrElse(fail("beforeAll did not populate the fixture"))

  /** The `snapshots` builder's own column order (see its scaladoc), needed to turn a `-json` row
    * (an object keyed by column name) back into the `List[Json]` shape `IcebergSnapshots.parse`
    * expects.
    */
  private val SnapshotColumns =
    List("snapshot_id", "parent_id", "seq", "ts_ms", "summary_json", "is_current")

  /** Splits `-json` stdout into one string per statement that produced output. Verified against
    * DuckDB 1.5.6: a statement with no result set (`CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`,
    * `CREATE SCHEMA`, `ATTACH`, `INSTALL`, `LOAD`) prints nothing at all; every statement that DOES
    * print one renders a single top-level JSON array, and a multi-row array continues on lines that
    * start directly with `{` - never `[` - so a line starting with `[` is exactly the start of a
    * new result. `CREATE SECRET` is the one DDL statement that prints (`[{"Success":true}]`), which
    * is why [[runJson]] always drops exactly one leading block for the prelude's secret.
    */
  private def splitJsonBlocks(stdout: String): List[String] =
    val lines  = stdout.linesIterator.toList
    val starts = lines.indices.filter(i => lines(i).startsWith("[")).toList
    starts.zip(starts.drop(1) :+ lines.size).map { case (from, to) =>
      lines.slice(from, to).mkString("\n")
    }

  /** Runs `setup` (statements that produce no output) followed by `queries` (one result each)
    * against a fresh attach, in `-json` mode, and returns each query's raw block alongside its
    * parsed [[Json]], in the same order as `queries`.
    */
  private def runJson(setup: List[String], queries: List[String]): List[(String, Json)] =
    // None of the builders in `IcebergCatalogSql` append a trailing `;` (a caller executes one
    // string as one whole statement, e.g. over the node's HTTP endpoint, with no concatenation in
    // sight). Here several queries share one script, so each needs its own `;` - without it two
    // adjacent queries fuse into one statement and the second's `WITH` reads as a stray continuation
    // of the first, which duckdb reports as a parser error rather than two clean results.
    val script =
      IcebergFixture.script(
        IcebergFixture.storagePrelude +: rw +: (setup ++ queries.map(q => s"$q;"))*
      )
    val r = IcebergFixture.duckdb(script, Seq("-json"))
    withClue(r.clue)(r.code shouldBe 0)
    val blocks = splitJsonBlocks(r.stdout)
    // +1: CREATE SECRET (in the storage prelude) is the only setup statement that prints anything.
    withClue(r.clue)(blocks.size shouldBe queries.size + 1)
    blocks.drop(1).map(raw => raw -> parser.parse(raw).getOrElse(fail(s"invalid JSON: $raw")))

  private def asRows(j: Json, columns: List[String]): List[List[Json]] =
    j.asArray.getOrElse(fail(s"expected a JSON array: $j")).toList.map { row =>
      val obj = row.asObject.getOrElse(fail(s"expected a JSON object row: $row")).toMap
      columns.map(col => obj.getOrElse(col, Json.Null))
    }

  private def asObjects(j: Json): List[Map[String, Json]] =
    j.asArray.getOrElse(fail(s"expected a JSON array: $j")).toList.map { row =>
      row.asObject.getOrElse(fail(s"expected a JSON object row: $row")).toMap
    }

  private def stringColumn(j: Json, key: String): List[String] =
    asObjects(j).map(o =>
      o.get(key).flatMap(_.asString).getOrElse(fail(s"missing/bad '$key' in $o"))
    )

  /** `id, v` rows sorted by `id`, joined into e.g. `"a,b,c"` - the shape every preview assertion in
    * this spec compares against.
    */
  private def previewLetters(j: Json): String =
    asObjects(j)
      .map { o =>
        val id = o.get("id").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(fail(s"bad id in $o"))
        val v  = o.get("v").flatMap(_.asString).getOrElse(fail(s"bad v in $o"))
        id -> v
      }
      .sortBy(_._1)
      .map(_._2)
      .mkString(",")

  private def diffTuples(j: Json): Set[(String, Int, String)] =
    asObjects(j).map { o =>
      val change = o
        .get(IcebergCatalogSql.ChangeColumn)
        .flatMap(_.asString)
        .getOrElse(fail(s"bad ${IcebergCatalogSql.ChangeColumn} in $o"))
      val id = o.get("id").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(fail(s"bad id in $o"))
      val v  = o.get("v").flatMap(_.asString).getOrElse(fail(s"bad v in $o"))
      (change, id, v)
    }.toSet

  private def parseSnapshots(j: Json): List[IcebergSnapshot] =
    IcebergSnapshots.parse(asRows(j, SnapshotColumns)) match
      case Right(v) => v
      case Left(e)  => fail(s"snapshot parse failed: $e, raw=$j")

  /** Creates the table, runs the mutation sequence, and captures every builder's output needed by
    * the cases below - two `-json` invocations, because the second one's queries (`preview` by
    * snapshot id, `diff`, `AtOrBefore`) need snapshot ids only the first invocation's `history`
    * result reveals. Iceberg REST catalog state lives outside the duckdb process, so a fresh attach
    * in the second invocation sees exactly what the first one committed.
    */
  override def beforeAll(): Unit =
    if available then
      val setup = List(
        s"""CREATE SCHEMA IF NOT EXISTS "$alias"."$schema";""",
        s"CREATE TABLE ${table(tbl)} (id INTEGER, v VARCHAR);",
        s"INSERT INTO ${table(tbl)} VALUES (1, 'a'), (2, 'b'), (3, 'c');",
        s"INSERT INTO ${table(tbl)} VALUES (4, 'd');",
        s"UPDATE ${table(tbl)} SET v = 'B' WHERE id = 2;",
        s"DELETE FROM ${table(tbl)} WHERE id = 3;"
      )
      val historyQuery =
        IcebergCatalogSql.snapshots(alias, schema, tbl, SnapshotFilter.Page(None, None), 100)
      val filesQuery   = IcebergCatalogSql.files(alias, schema, tbl)
      val schemasQuery = IcebergCatalogSql.schemas(alias)
      val tablesQuery  = IcebergCatalogSql.tables(alias, schema)
      val columnsQuery = IcebergCatalogSql.columns(alias, schema, tbl)

      val first = runJson(
        setup,
        List(historyQuery, filesQuery, schemasQuery, tablesQuery, columnsQuery)
      )
      val List(
        (historyRaw, historyJson),
        (_, filesJson),
        (_, schemasJson),
        (_, tablesJson),
        (_, columnsJson)
      ) = first: @unchecked

      val history      = parseSnapshots(historyJson)
      val appends      = history.filter(_.operation.contains("append")).sortBy(_.sequence)
      val firstAppend  = appends.headOption.getOrElse(fail(s"no append snapshot in $history"))
      val secondAppend = appends.lift(1).getOrElse(fail(s"no second append snapshot in $history"))
      val current      = history.find(_.current).getOrElse(fail(s"no current snapshot in $history"))
      val overwrite    =
        history
          .find(_.operation.contains("overwrite"))
          .getOrElse(fail(s"no overwrite snapshot in $history"))

      // The overwrite's own sequence number, read back from the parsed history rather than assumed
      // from commit order, is what the `Page` filter's `beforeSeq` is exercised against.
      val pageQuery = IcebergCatalogSql.snapshots(
        alias,
        schema,
        tbl,
        SnapshotFilter.Page(Some(overwrite.sequence), None),
        100
      )
      val atOrBeforeQuery = IcebergCatalogSql.snapshots(
        alias,
        schema,
        tbl,
        SnapshotFilter.AtOrBefore(secondAppend.timestampMs + 1),
        1
      )
      val preview1Query =
        IcebergCatalogSql.preview(alias, schema, tbl, Some(firstAppend.snapshotId), 1000)
      val preview2Query =
        IcebergCatalogSql.preview(alias, schema, tbl, Some(secondAppend.snapshotId), 1000)
      val previewCurrentQuery = IcebergCatalogSql.preview(alias, schema, tbl, None, 1000)
      val diffQuery           = IcebergCatalogSql.diff(
        alias,
        schema,
        tbl,
        secondAppend.snapshotId,
        current.snapshotId,
        None,
        1000
      )
      val currentQuery =
        IcebergCatalogSql.snapshots(alias, schema, tbl, SnapshotFilter.Current, 1)

      val second = runJson(
        Nil,
        List(
          pageQuery,
          atOrBeforeQuery,
          preview1Query,
          preview2Query,
          previewCurrentQuery,
          diffQuery,
          currentQuery
        )
      )
      val List(
        (_, pageJson),
        (_, atOrBeforeJson),
        (_, preview1Json),
        (_, preview2Json),
        (_, previewCurrentJson),
        (_, diffJson),
        (_, currentJson)
      ) = second: @unchecked

      captured = Some(
        Captured(
          historyRaw,
          history,
          pageJson,
          filesJson,
          schemasJson,
          tablesJson,
          columnsJson,
          atOrBeforeJson,
          preview1Json,
          preview2Json,
          previewCurrentJson,
          diffJson,
          currentJson
        )
      )

  /** `DROP SCHEMA ... CASCADE` is not supported for Iceberg schemas (see `IcebergRestE2ESpec`), so
    * the table is dropped explicitly before the (now empty) schema. Both use `IF EXISTS`: a
    * `beforeAll` that failed partway through (e.g. after `CREATE SCHEMA` but before `CREATE TABLE`)
    * must not turn `afterAll` into a second, unrelated failure on top of the real one.
    */
  override def afterAll(): Unit =
    if available then
      val r = IcebergFixture.duckdb(
        IcebergFixture.script(
          IcebergFixture.storagePrelude,
          rw,
          s"DROP TABLE IF EXISTS ${table(tbl)};",
          s"""DROP SCHEMA IF EXISTS "$alias"."$schema";"""
        )
      )
      assert(r.code == 0, s"teardown of $schema failed: ${r.clue}")

  "the history builder" should "return every snapshot newest first, with only the newest current" in {
    requireFixture()
    c.history.map(_.operation) shouldBe List(
      Some("delete"),
      Some("overwrite"),
      Some("append"),
      Some("append")
    )
    c.history.count(_.current) shouldBe 1
    c.history.head.current shouldBe true
  }

  "the history builder's Page filter" should "return only the snapshots before the overwrite" in {
    requireFixture()
    val overwriteSeq =
      c.history
        .find(_.operation.contains("overwrite"))
        .getOrElse(fail("no overwrite in history"))
        .sequence
    val page = parseSnapshots(c.pageJson)
    page.map(_.operation) shouldBe List(Some("append"), Some("append"))
    // Ties the result back to the exact `beforeSeq` the query ran with (read from the live history,
    // not assumed from commit order): every returned row's own sequence is strictly below it.
    page.forall(_.sequence < overwriteSeq) shouldBe true
  }

  "the history builder's Current filter" should "return exactly the current snapshot" in {
    requireFixture()
    val current  = c.history.find(_.current).getOrElse(fail("no current snapshot in history"))
    val resolved = parseSnapshots(c.currentJson)
    resolved.map(_.snapshotId) shouldBe List(current.snapshotId)
    resolved.head.current shouldBe true
  }

  "the history builder's AtOrBefore filter" should "resolve to the second append" in {
    requireFixture()
    val secondAppend = c.history.filter(_.operation.contains("append")).sortBy(_.sequence).apply(1)
    val resolved     = parseSnapshots(c.atOrBeforeJson)
    resolved.map(_.snapshotId) shouldBe List(secondAppend.snapshotId)
  }

  "the preview builder" should "read each requested snapshot's rows, and the current state by default" in {
    requireFixture()
    previewLetters(c.preview1Json) shouldBe "a,b,c"
    previewLetters(c.preview2Json) shouldBe "a,b,c,d"
    previewLetters(c.previewCurrentJson) shouldBe "a,B,d"
  }

  "the diff builder" should "classify every changed row as removed or added" in {
    requireFixture()
    diffTuples(c.diffJson) shouldBe Set(("removed", 2, "b"), ("removed", 3, "c"), ("added", 2, "B"))
  }

  "the files builder" should "list at least one live data file and one position-delete file" in {
    requireFixture()
    val contents = stringColumn(c.filesJson, "content")
    // DuckDB 1.5.6's `iceberg_metadata()` prints "EXISTING" in `content` for a live data file;
    // the builder folds it to "DATA" through `manifest_content`, so "EXISTING" must never surface.
    contents should contain("DATA")
    contents should not contain "EXISTING"
    contents should contain("POSITION_DELETES")
  }

  "the schemas, tables and columns builders" should "see what beforeAll created" in {
    requireFixture()
    // `schemas` lists every schema in the catalog, not just this run's - a long-lived REST catalog
    // (or a prior run's leftovers) legitimately has others, so this only checks ours is among them.
    stringColumn(c.schemasJson, "schema_name") should contain(schema)
    stringColumn(c.tablesJson, "table_name") shouldBe List(tbl)
    stringColumn(c.columnsJson, "column_name") shouldBe List("id", "v")
  }

  "the history builder's own output" should "never carry storage_credentials or config" in {
    requireFixture()
    c.historyRaw should not include "storage_credentials"
    c.historyRaw should not include "\"config\""
  }
