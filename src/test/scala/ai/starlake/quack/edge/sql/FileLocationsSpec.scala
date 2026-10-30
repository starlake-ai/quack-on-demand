package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** The two file-access shapes the gateway reads completely (Task 12): a reader call's locations and
  * a lone COPY's location and table side. Anything else answers None, which keeps the statement
  * refused.
  */
class FileLocationsSpec extends AnyFlatSpec with Matchers:

  private def readerCall(sql: String): FunctionCalls.Call =
    FunctionCalls.scan(sql).calls.find(c => FileLocations.Readers(c.name)).get

  private def locations(sql: String): Option[List[String]] =
    FunctionCalls.locationArguments(readerCall(sql))

  "locationArguments" should "read a literal, a decoded literal and a list of literals" in {
    locations("SELECT * FROM read_parquet('s3://acme-etl/in/x.parquet')") shouldBe
      Some(List("s3://acme-etl/in/x.parquet"))
    locations("SELECT * FROM read_csv('/srv/qod/acme/in/it''s.csv')") shouldBe
      Some(List("/srv/qod/acme/in/it's.csv"))
    locations("SELECT * FROM read_csv(e'/srv/qod/acme/in/\\x61.csv')") shouldBe
      Some(List("/srv/qod/acme/in/a.csv"))
    locations("SELECT * FROM read_csv($$/srv/qod/acme/in/x.csv$$)") shouldBe
      Some(List("/srv/qod/acme/in/x.csv"))
    locations(
      "SELECT * FROM read_parquet(['s3://acme-etl/a.parquet', 's3://acme-etl/b.parquet'])"
    ) shouldBe
      Some(List("s3://acme-etl/a.parquet", "s3://acme-etl/b.parquet"))
  }

  it should "read constant named options after the location" in {
    locations(
      "SELECT * FROM read_csv('s3://acme-etl/in/x.csv', header = true, delim := '|', " +
        "columns = {'a': 'INTEGER'}, names => ['a'], skip = -1)"
    ) shouldBe Some(List("s3://acme-etl/in/x.csv"))
  }

  it should "answer None for every argument list it cannot read completely" in
    List(
      "SELECT * FROM read_csv('s3://acme-etl/x' || '.csv')",
      "SELECT * FROM read_csv(path)",
      "SELECT * FROM read_csv(\"path\")",
      "SELECT * FROM read_csv(CAST('s3://acme-etl/x.csv' AS VARCHAR))",
      "SELECT * FROM read_csv(lower('S3://ACME-ETL/X.CSV'))",
      "SELECT * FROM read_csv(?)",
      "SELECT * FROM read_csv(x's3')",
      "SELECT * FROM read_csv('s3://acme-etl/x'\n'.csv')",
      "SELECT * FROM read_csv([])",
      "SELECT * FROM read_csv(['s3://acme-etl/x.csv', p])",
      "SELECT * FROM read_csv('s3://acme-etl/x.csv', '/etc/hosts')",
      "SELECT * FROM read_csv('s3://acme-etl/x.csv', header = h)",
      "SELECT * FROM read_csv('s3://acme-etl/x.csv', header = lower('x'))",
      "SELECT * FROM read_csv('s3://acme-etl/x.csv'",
      "SELECT * FROM read_csv()"
    ).foreach(sql => withClue(sql)(locations(sql) shouldBe None))

  "copy" should "read the location and the table side of a lone COPY" in {
    FileLocations.copy("COPY lake.main.events FROM 's3://acme-etl/in/e.csv'") shouldBe
      Some(
        FileLocations
          .CopyUse("s3://acme-etl/in/e.csv", write = false, "INSERT INTO lake.main.events SELECT 1")
      )
    FileLocations.copy("COPY \"events\" (id, v) FROM 's3://acme-etl/in/e.csv' (HEADER)") shouldBe
      Some(
        FileLocations.CopyUse(
          "s3://acme-etl/in/e.csv",
          write = false,
          "INSERT INTO \"events\" SELECT 1"
        )
      )
    FileLocations.copy("COPY orders TO 's3://acme-etl/out/o.parquet' (FORMAT parquet)") shouldBe
      Some(
        FileLocations.CopyUse("s3://acme-etl/out/o.parquet", write = true, "SELECT * FROM orders")
      )
    FileLocations.copy(
      "COPY (SELECT id FROM orders WHERE id > 1) TO '/srv/qod/acme/out/' " +
        "WITH (FORMAT parquet, PARTITION_BY (id), FILENAME_PATTERN 'part_{i}')"
    ) shouldBe Some(
      FileLocations.CopyUse(
        "/srv/qod/acme/out/",
        write = true,
        "SELECT id FROM orders WHERE id > 1"
      )
    )
    FileLocations.copy("COPY orders TO 's3://acme-etl/out/o.csv';") shouldBe
      Some(FileLocations.CopyUse("s3://acme-etl/out/o.csv", write = true, "SELECT * FROM orders"))
    // text options may hold a slash under a core format: DuckDB never reads them as a path
    FileLocations.copy(
      "COPY events FROM 's3://acme-etl/in/e.csv' (DATEFORMAT '%d/%m/%Y', DELIMITER '/')"
    ) shouldBe Some(
      FileLocations.CopyUse("s3://acme-etl/in/e.csv", write = false, "INSERT INTO events SELECT 1")
    )
  }

  it should "answer None for every other COPY shape" in
    List(
      "EXPLAIN ANALYZE COPY orders TO 's3://acme-etl/out/o.csv'",
      "PREPARE p AS COPY orders TO 's3://acme-etl/out/o.csv'",
      "SELECT 1; COPY orders TO 's3://acme-etl/out/o.csv'",
      "COPY orders TO 's3://acme-etl/out/o.csv'; SELECT 1",
      "COPY FROM DATABASE a TO b",
      "COPY (SELECT 1) FROM 's3://acme-etl/in/x.csv'",
      "COPY orders TO 's3://acme-etl/out/' || 'o.csv'",
      "COPY orders TO 's3://acme-etl/out/'\n'o.csv'",
      "COPY orders TO x's3'",
      "COPY orders TO \"s3://acme-etl/out/o.csv\"",
      "COPY orders TO 's3://acme-etl/out/' (FILENAME_PATTERN '../x_{i}')",
      "COPY orders TO 's3://acme-etl/out/' (FILENAME_PATTERN 'sub/x_{i}')",
      "COPY orders TO 's3://acme-etl/out/' (FORMAT csv, PARTITION_BY (v), FILENAME_PATTERN '../x')",
      "COPY orders TO 's3://acme-etl/out/o.csv' (HEADER, REJECTS_TABLE x'2F2E2E')",
      "COPY orders TO 's3://acme-etl/out/o.csv' (FORMAT csv) extra",
      "COPY orders TO 's3://acme-etl/out/o.csv' (FORMAT csv",
      "COPY (SELECT 1 TO 's3://acme-etl/out/o.csv'",
      "COPY TO 's3://acme-etl/out/o.csv'",
      // the legacy and specialized option forms are not read here
      "COPY orders TO 's3://acme-etl/out/o.csv' DELIMITER '|' HEADER",
      "COPY orders FROM 's3://acme-etl/in/o.csv' USING DELIMITERS '|'"
    ).foreach(sql => withClue(sql)(FileLocations.copy(sql) shouldBe None))

  // qod-node's COPY option rule (screen_rules.cpp CopyOptions), applied at the gateway too.
  private def admitsOptions(sql: String): Boolean = FileLocations.copy(sql).isDefined

  "the COPY option rule" should "take FILENAME_PATTERN and FILE_EXTENSION as one literal string" in {
    List(
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN 'x_{i}')",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILE_EXTENSION 'pq')",
      "COPY t TO 's3://b/out/' (FORMAT parquet, \"filename_pattern\" e'x_{i}')"
    ).foreach(sql => withClue(sql)(admitsOptions(sql) shouldBe true))
    List(
      // a list, a call, a cast, a concatenation, a bare word, a number, a blob, no value
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN ['x_{i}'])",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN lower('X_{i}'))",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN 'x_{i}'::VARCHAR)",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN 'x' || '_{i}')",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN part)",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILE_EXTENSION 1)",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILE_EXTENSION x'7071')",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN)",
      // a path of its own, as written or decoded
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILE_EXTENSION '../pq')",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN e'\\x2F..')",
      "COPY t TO 's3://b/out/' (FORMAT parquet, FILENAME_PATTERN 'a\\b')"
    ).foreach(sql => withClue(sql)(admitsOptions(sql) shouldBe false))
  }

  it should "check every other option as a literal, as spelled and as decoded" in {
    List(
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE 'rej')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE rej)",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE x'41')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE b'0101')",
      "COPY t FROM 's3://b/i.csv' (UNION_BY_NAME true, TYPES {'a': 'INTEGER'}, NAMES ['a', b])",
      "COPY t FROM 's3://b/i.csv' (HIVE_TYPES {a: 'INTEGER'}, FILENAME false, ENCODING_X -1)"
    ).foreach(sql => withClue(sql)(admitsOptions(sql) shouldBe true))
    List(
      // computed: a call, an operator, a cast, a parameter, a qualified name
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE lower('x'))",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE 'a' || 'b')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE 'x'::VARCHAR)",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE ?)",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE a.b)",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE [concat('a', 'b')])",
      // a path in any text: a literal, a decoded blob, a name, a struct field name
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE '/x')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE x'2F78')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE x'00')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE \"../x\")",
      "COPY t FROM 's3://b/i.csv' (TYPES {'../a': 'INTEGER'})",
      "COPY t FROM 's3://b/i.csv' (NAMES ['a', 'b/c'])",
      // a hex or bit literal DuckDB would refuse
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE x'4')",
      "COPY t TO 's3://b/o.csv' (REJECTS_TABLE b'012')"
    ).foreach(sql => withClue(sql)(admitsOptions(sql) shouldBe false))
  }

  it should "free the node's non-path options under the core formats only" in {
    List(
      // csv by extension, by FORMAT, and when the file name has no extension
      "COPY t TO 's3://b/o.csv' (DELIMITER '/', COMPRESSION x'00')",
      "COPY t TO 's3://b/o.csv.gz' (DELIMITER '/')",
      "COPY t TO 's3://b/out/' (DELIMITER '/')",
      "COPY t TO 's3://b/o.dat' (FORMAT 'CSV', DELIMITER '/')",
      "COPY t TO 's3://b/o.dat' (FORMAT \"csv\", DELIMITER '/')",
      "COPY t TO 's3://b/o.parquet' (KV_METADATA {k: '/v'}, PARTITION_BY lower(a))",
      "COPY t TO 's3://b/o.json' (ORDER BY (a DESC), PARTITION BY a)"
    ).foreach(sql => withClue(sql)(admitsOptions(sql) shouldBe true))
    List(
      // an extension's format, or a file extension no core function claims
      "COPY t TO 's3://b/o.xlsx' (DELIMITER '/')",
      "COPY t TO 's3://b/o.csv' (FORMAT xlsx, DELIMITER '/')",
      "COPY t TO 's3://b/o.dat' (FORMAT lower('CSV'), DELIMITER '/')",
      "COPY t TO 's3://b/o.xlsx' (PARTITION_BY lower(a))"
    ).foreach(sql => withClue(sql)(admitsOptions(sql) shouldBe false))
    // under a non-core format, a literal option holding no path still passes
    admitsOptions(
      "COPY t TO 's3://b/o.xlsx' (HEADER true, SHEET 'Data', PARTITION_BY (a))"
    ) shouldBe
      true
  }

  it should "refuse a bare special value word, which an expression reads as a call" in {
    List("current_schema", "CURRENT_DATE", "user", "session_user", "current_catalog").foreach { w =>
      withClue(w)(admitsOptions(s"COPY t TO 's3://b/o.xlsx' (SHEET $w)") shouldBe false)
    }
    admitsOptions("COPY t TO 's3://b/o.xlsx' (SHEET data)") shouldBe true
  }

  // The rule per option class, as screen_rules.cpp CopyOptions / CopyFormat / LiteralOption apply
  // it; a change to either side's rule must change these cases. Two cases are stricter than the
  // node on purpose (the gateway's token reader does not take them as literals): a parenthesized
  // file name and a `row(...)` spelled as a call.
  it should "apply each option class's rule, as qod-node does" in {
    val cases = List(
      // file name options: one VARCHAR literal holding no path, under every format
      "COPY t TO 's3://b/out/' (FORMAT xlsx, FILENAME_PATTERN 'x_{i}')"  -> true,
      "COPY t TO 's3://b/out/' (FORMAT csv, FILENAME_PATTERN ('x_{i}'))" -> false,
      "COPY t TO 's3://b/out/' (FORMAT csv, FILE_EXTENSION n'csv')"      -> true,
      "COPY t TO 's3://b/out/' (FORMAT csv, FILE_EXTENSION 'c/v')"       -> false,
      // a non-path option under a core format: any value
      "COPY t TO 's3://b/o.parquet' (COMPRESSION lower('ZSTD'))" -> true,
      "COPY t TO 's3://b/o.ndjson' (DATE_FORMAT '%Y/%m/%d')"     -> true,
      "COPY t TO 's3://b/o.csv.zst' (DELIMITER '/')"             -> true,
      "COPY t TO 's3://b/o.xlsx' (FORMAT csv, QUOTE '/')"        -> true,
      // the same option under any other format: a literal holding no path
      "COPY t TO 's3://b/o.parquet' (FORMAT xlsx, COMPRESSION lower('ZSTD'))" -> false,
      "COPY t TO 's3://b/o.tsv' (DATE_FORMAT '%Y/%m/%d')"                     -> false,
      "COPY t TO 's3://b/o.tsv' (DATE_FORMAT '%Y-%m-%d')"                     -> true,
      "COPY t TO 's3://b/o.csv' (FORMAT 'xlsx.gz', QUOTE '/')"                -> false,
      // any other option, under any format: a literal holding no path
      "COPY t FROM 's3://b/i.csv' (HIVE_PARTITIONING true, UNION_BY_NAME 1)" -> true,
      "COPY t FROM 's3://b/i.csv' (FILENAME 'f/n')"                          -> false,
      "COPY t FROM 's3://b/i.csv' (TYPES row('a', 'b'))"                     -> false,
      "COPY t FROM 's3://b/i.csv' (TYPES ('a', 'b'))"                        -> true
    )
    cases.foreach { (sql, admitted) =>
      withClue(sql)(admitsOptions(sql) shouldBe admitted)
    }
  }

  it should "not read a COPY of a listing or a description" in
    List(
      "COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "COPY (SHOW TABLES FROM tpch1) TO 's3://acme-etl/out/t.csv'",
      "COPY (show all tables) TO 's3://acme-etl/out/t.csv'",
      "COPY (DESCRIBE orders) TO 's3://acme-etl/out/t.csv'",
      "COPY (DESC orders) TO 's3://acme-etl/out/t.csv'",
      "COPY (SUMMARIZE orders) TO 's3://acme-etl/out/t.csv'",
      "COPY (SELECT * FROM (SHOW TABLES)) TO 's3://acme-etl/out/t.csv'",
      "COPY (WITH s AS (DESCRIBE orders) SELECT * FROM s) TO 's3://acme-etl/out/t.csv'"
    ).foreach(sql => withClue(sql)(FileLocations.copy(sql) shouldBe None))

  "unreadCopy" should "flag every COPY the reader cannot take as the lone statement" in
    List(
      "SELECT 1; COPY t TO 's3://acme-etl/out/t.csv'",
      "COPY t TO 's3://acme-etl/out/t.csv'; SELECT 1",
      "EXPLAIN ANALYZE COPY t TO 's3://acme-etl/out/t.csv'",
      "PREPARE p AS COPY t TO 's3://acme-etl/out/t.csv'",
      "COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "COPY (DESCRIBE orders) TO 's3://acme-etl/out/t.csv'",
      "COPY (SELECT 1) TO 's3://acme-etl/out/' || 't.csv'",
      "COPY FROM DATABASE a TO b"
    ).foreach(sql => withClue(sql)(FileLocations.unreadCopy(sql) shouldBe true))

  it should "leave a lone COPY it reads, and every statement that is no COPY" in
    List(
      "COPY (SELECT * FROM main.orders) TO 's3://acme-etl/out/o.csv'",
      "COPY lake.main.orders TO 's3://acme-etl/out/o.parquet' (FORMAT parquet)",
      "COPY lake.main.events FROM 's3://acme-etl/in/e.csv'",
      "SELECT 'COPY t TO x'",
      "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')",
      "SELECT 1; SELECT 2"
    ).foreach(sql => withClue(sql)(FileLocations.unreadCopy(sql) shouldBe false))

  "the COPY option lists" should "be qod-node's, name for name" in {
    val source = Files.readString(Paths.get("native/qod-node/src/screen_rules.cpp"))
    def names(array: String): Set[String] =
      val start = source.indexOf(s"const char *const $array[] = {")
      withClue(s"$array not found in screen_rules.cpp")(start should be >= 0)
      val body = source.substring(start, source.indexOf("};", start))
      "\"([^\"]*)\"".r.findAllMatchIn(body.substring(body.indexOf('{'))).map(_.group(1)).toSet
    FileLocations.CopyNonPathOptions shouldBe names("kCopyNonPathOptions")
    FileLocations.CoreCopyFormats shouldBe names("kCoreCopyFormats")
    FileLocations.CopyFileNameOptions shouldBe names("kCopyFileNameOptions")
  }

  "Readers" should "be the file functions of class File, and only those" in {
    FileLocations.Readers should contain allOf ("read_parquet", "read_csv", "glob", "read_text")
    List("sql_auto_complete", "ducklake_scan", "iceberg_scan", "iceberg_metadata", "read_xlsx")
      .foreach(n => withClue(n)(FileLocations.Readers should not contain n))
    FileLocations.Readers.subsetOf(FileAccessScreen.FileFunctions) shouldBe true
  }
