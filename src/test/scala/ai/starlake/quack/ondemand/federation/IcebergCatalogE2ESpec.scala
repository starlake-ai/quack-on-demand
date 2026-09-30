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

import scala.sys.process.{Process, ProcessLogger}

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
  * VARCHAR text in that mode. [[duckdbJson]] is a second, minimal CLI runner for that reason alone;
  * [[IcebergFixture.duckdb]] (`-list -noheader`) is reused as-is for the schema/table teardown,
  * which needs no typed columns.
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
      diffJson: Json
  )

  private var captured: Option[Captured] = None

  private def c: Captured = captured.getOrElse(fail("beforeAll did not populate the fixture"))

  /** The `snapshots` builder's own column order (see its scaladoc), needed to turn a `-json` row
    * (an object keyed by column name) back into the `List[Json]` shape `IcebergSnapshots.parse`
    * expects.
    */
  private val SnapshotColumns =
    List("snapshot_id", "parent_id", "seq", "ts_ms", "summary_json", "is_current")

  private val duckdbBin: String = sys.env.getOrElse("DUCKDB_BIN", "duckdb")

  /** Run SQL through the duckdb CLI's stdin in `-json` mode. Same shape as `IcebergFixture.duckdb`
    * (stdin script, streams kept apart, non-zero exit on any failed statement while the CLI keeps
    * running past it) - only the output format differs.
    */
  private def duckdbJson(sql: String): DuckdbRun =
    val out  = new StringBuilder
    val err  = new StringBuilder
    val log  = ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    val in   = new java.io.ByteArrayInputStream(sql.getBytes("UTF-8"))
    val code = (Process(Seq(duckdbBin, "-json")) #< in).!(log)
    DuckdbRun(code, out.toString, err.toString)

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
    val r = duckdbJson(script)
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
      // The commit order is deterministic (append, append, overwrite, delete => seq 1..4), so the
      // overwrite's sequence number for the `Page` filter is known without reading it back first.
      val historyQuery =
        IcebergCatalogSql.snapshots(alias, schema, tbl, SnapshotFilter.Page(None, None), 100)
      val pageQuery =
        IcebergCatalogSql.snapshots(alias, schema, tbl, SnapshotFilter.Page(Some(3L), None), 100)
      val filesQuery   = IcebergCatalogSql.files(alias, schema, tbl)
      val schemasQuery = IcebergCatalogSql.schemas(alias)
      val tablesQuery  = IcebergCatalogSql.tables(alias, schema)
      val columnsQuery = IcebergCatalogSql.columns(alias, schema, tbl)

      val first = runJson(
        setup,
        List(historyQuery, pageQuery, filesQuery, schemasQuery, tablesQuery, columnsQuery)
      )
      val List(
        (historyRaw, historyJson),
        (_, pageJson),
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

      val second = runJson(
        Nil,
        List(atOrBeforeQuery, preview1Query, preview2Query, previewCurrentQuery, diffQuery)
      )
      val List(
        (_, atOrBeforeJson),
        (_, preview1Json),
        (_, preview2Json),
        (_, previewCurrentJson),
        (_, diffJson)
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
          diffJson
        )
      )

  /** `DROP SCHEMA ... CASCADE` is not supported for Iceberg schemas (see `IcebergRestE2ESpec`), so
    * the table is dropped explicitly before the (now empty) schema.
    */
  override def afterAll(): Unit =
    if available then
      val r = IcebergFixture.duckdb(
        IcebergFixture.script(
          IcebergFixture.storagePrelude,
          rw,
          s"DROP TABLE ${table(tbl)};",
          s"""DROP SCHEMA "$alias"."$schema";"""
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
    val page = parseSnapshots(c.pageJson)
    page.map(_.operation) shouldBe List(Some("append"), Some("append"))
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
    // DuckDB 1.5.6's `iceberg_metadata()` prints "EXISTING" for a live data file's `content` column,
    // never the literal "DATA" the Iceberg spec's FileContentType enum name would suggest - a
    // separate `manifest_content` column (not selected by this builder) is what carries "DATA" vs
    // "DELETE". "POSITION_DELETES" is unambiguous and is exactly what the builder selects. Verified
    // against this fixture on 2026-09-30; noted here because the brief this spec was written from
    // expected "DATA" and that string never appears in `content`.
    contents should contain("EXISTING")
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
