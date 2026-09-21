package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LockdownScreenSpec extends AnyFlatSpec with Matchers:

  private def denied(sql: String): Boolean = LockdownScreen.screen(sql, Set.empty).isDefined

  // Bucket-denial variant: 'lakebucket' holds DuckLake data, 'staging' does not.
  private def deniedB(sql: String): Boolean =
    LockdownScreen.screen(sql, Set("lakebucket")).isDefined

  "statement kinds" should "deny ATTACH/DETACH/INSTALL/LOAD, admit ordinary SQL" in {
    denied("ATTACH 'x.db' AS other") shouldBe true
    denied("detach other") shouldBe true
    denied("INSTALL spatial") shouldBe true
    denied("LOAD spatial") shouldBe true
    denied("SELECT 1") shouldBe false
    denied("INSERT INTO t VALUES (1)") shouldBe false
    denied("  WITH x AS (SELECT 1) SELECT * FROM x") shouldBe false
  }

  "settings" should "deny protected SET/RESET/PRAGMA, admit benign settings" in {
    denied("SET disabled_filesystems = ''") shouldBe true
    denied("set LOCK_CONFIGURATION = false") shouldBe true
    denied("RESET enable_external_access") shouldBe true
    denied("PRAGMA temp_directory = '/tmp/x'") shouldBe true
    denied("SET allowed_directories = ['/']") shouldBe true
    denied("SET schema = 'tpch1'") shouldBe false
    denied("PRAGMA table_info('t')") shouldBe false
  }

  it should "deny resource settings (memory / threads / temp size)" in {
    denied("SET memory_limit = '100GB'") shouldBe true
    denied("SET max_memory = '100GB'") shouldBe true
    denied("RESET memory_limit") shouldBe true
    denied("PRAGMA memory_limit = '100GB'") shouldBe true
    denied("SET threads = 64") shouldBe true
    denied("SET worker_threads = 64") shouldBe true
    denied("SET max_temp_directory_size = '1TB'") shouldBe true
    // resource-setting names in ordinary SQL stay admitted
    denied("SELECT memory_limit FROM settings_view") shouldBe false
    denied("SELECT * FROM t WHERE name = 'threads'") shouldBe false
  }

  "functions" should "deny local-path reads anywhere in the statement" in {
    denied("SELECT * FROM read_text('/etc/passwd')") shouldBe true
    denied("SELECT read_blob('secrets.bin')") shouldBe true
    denied("SELECT * FROM t WHERE c IN (SELECT x FROM glob('/data/*'))") shouldBe true
    denied("SELECT getenv('HOME')") shouldBe true
    denied("SELECT * FROM READ_CSV('/tmp/x.csv')") shouldBe true
  }

  it should "admit object-store URL literals, fail closed on non-literals" in {
    denied("SELECT * FROM read_parquet('s3://bucket/k.parquet')") shouldBe false
    denied("SELECT * FROM read_csv('https://x.example/data.csv')") shouldBe false
    denied("SELECT * FROM read_csv('gs://b/x.csv', header = true)") shouldBe false
    denied("SELECT * FROM read_parquet(other_col)") shouldBe true
    denied("SELECT * FROM read_parquet(concat('s3://b/', f))") shouldBe true
    denied("SELECT * FROM read_csv(['s3://b/a.csv', '/tmp/b.csv'])") shouldBe true
  }

  it should "not deny identifiers that merely contain a denied name" in {
    denied("SELECT read_text_total FROM stats") shouldBe false
    denied("SELECT * FROM my_read_csv_results") shouldBe false
  }

  "multiple occurrences" should "screen every call of a denied function" in {
    denied("SELECT * FROM read_csv('s3://b/a.csv'), read_csv('/etc/passwd')") shouldBe true
    denied("SELECT * FROM read_csv('s3://a'), read_csv('s3://b')") shouldBe false
  }

  "leading trivia" should "not defeat the first-token and settings checks" in {
    denied("/* x */ ATTACH 'y.db' AS z") shouldBe true
    denied("/* x */ SET disabled_filesystems=''") shouldBe true
    denied("-- c\nATTACH 'x' AS y") shouldBe true
    denied("\uFEFF\u00A0ATTACH 'x' AS y") shouldBe true
    denied("/* a /* nested */ b */ ATTACH 'x' AS y") shouldBe true
    denied("/* x */ SELECT 1") shouldBe false
  }

  it should "not be defeated by a leading word joiner (U+2060), a Cf character once missed by name" in {
    // U+2060 is neither `Character.isWhitespace` nor `SPACE_SEPARATOR`; confirmed separately
    // against a real DuckDB CLI that a write prefixed with it still executes. isTriviaSpace was
    // broadened from two hardcoded Cf characters (BOM, ZWSP) to the whole Cf (FORMAT) category, so
    // this deployment-lockdown gate needs the same regression witness CatalogWriteScreenSpec pins.
    denied("\u2060ATTACH 'x' AS y") shouldBe true
  }

  "quoted identifiers" should "not evade the function screen" in {
    denied("SELECT \"read_text\"('/etc/passwd')") shouldBe true
    denied("SELECT \"my_read_text\" FROM t") shouldBe false
  }

  "chained statements" should "screen each semicolon-separated statement" in {
    denied("SELECT 1; ATTACH 'x' AS y") shouldBe true
    denied("SELECT 1; SET disabled_filesystems=''") shouldBe true
    denied("SELECT 1; SELECT 2") shouldBe false
    denied("SELECT 'a;b'; SELECT 2") shouldBe false
  }

  "settings statements" should "still screen denied functions in their values" in {
    denied("SET my_var = getenv('HOME')") shouldBe true
    denied("SET enable_progress_bar = true") shouldBe false
    denied("PRAGMA table_info('t')") shouldBe false
  }

  "bare-path FROM" should "deny local replacement scans, admit remote and non-path literals" in {
    denied("SELECT * FROM '/etc/passwd.parquet'") shouldBe true
    denied("SELECT * FROM'/etc/passwd.parquet'") shouldBe true
    denied("CREATE TABLE t AS SELECT * FROM '/etc/x.parquet'") shouldBe true
    denied("SELECT * FROM 's3://b/x.parquet'") shouldBe false
    denied("SELECT * FROM t WHERE path = '/etc/passwd'") shouldBe false
    denied("SELECT * FROM customer") shouldBe false
    denied("SELECT trim(leading '/' from '/a/b.csv')") shouldBe false
  }

  it should "deny dollar-quoted and escape-string replacement scans (deep-review H4)" in {
    // DuckDB's replacement scan also accepts $$...$$ / $tag$...$tag$ and e'...' literals in
    // FROM position; the single-quote-only regex admitted them, an open local-file read on
    // lockdown pools. These forms have no legitimate non-path use in FROM position, so they
    // fail closed: denied unless provably remote.
    denied("SELECT * FROM $$/etc/passwd$$") shouldBe true
    denied("SELECT * FROM $tag$/etc/passwd$tag$") shouldBe true
    denied("SELECT * FROM e'/private/var/db/secret.csv'") shouldBe true
    // escape sequences can encode the path, so a non-remote e-string denies even when it
    // does not LOOK like a path
    denied("SELECT * FROM e'\\x2fetc\\x2fpasswd'") shouldBe true
    // the remote exemption still applies to the new forms
    denied("SELECT * FROM $$s3://b/x.parquet$$") shouldBe false
    deniedB("SELECT * FROM $$s3://lakebucket/x.parquet$$") shouldBe true
  }

  "COPY" should "deny local paths, admit remote copies with options and pure table copies" in {
    denied("COPY t FROM '/etc/x.csv'") shouldBe true
    denied("COPY t TO '/tmp/x'") shouldBe true
    denied("COPY t FROM '/etc/x.csv' (DELIMITER ',')") shouldBe true
    denied("COPY t FROM 's3://b/x.csv'") shouldBe false
    denied("COPY t TO 's3://b/x.csv' (DELIMITER '|')") shouldBe false
    denied("COPY a FROM b") shouldBe false
  }

  "read_text/read_blob" should "honor the URL exemption" in {
    denied("SELECT read_text('https://x/f.txt')") shouldBe false
    denied("SELECT read_text('/etc/passwd')") shouldBe true
  }

  "reasons" should "name the blocked construct" in {
    LockdownScreen.screen("ATTACH 'x' AS y", Set.empty).get should include("ATTACH")
    LockdownScreen
      .screen("SET lock_configuration=false", Set.empty)
      .get should include("lock_configuration")
    LockdownScreen.screen("SELECT read_text('/x')", Set.empty).get should include("read_text")
  }

  // ---- DuckLake bucket denial ----

  "bucket denial" should "deny reads on a DuckLake bucket under every object-store scheme" in {
    deniedB("SELECT * FROM read_parquet('s3://lakebucket/db-x/main/t/f.parquet')") shouldBe true
    deniedB("SELECT * FROM read_parquet('s3a://lakebucket/x.parquet')") shouldBe true
    deniedB("SELECT * FROM read_parquet('r2://lakebucket/x.parquet')") shouldBe true
    deniedB("SELECT * FROM read_csv('gs://lakebucket/x.csv')") shouldBe true
    deniedB("SELECT * FROM read_parquet('S3://LAKEBUCKET/X.PARQUET')") shouldBe true
    deniedB("SELECT \"read_parquet\"('s3://lakebucket/x.parquet')") shouldBe true
    deniedB("SELECT read_text('az://lakebucket/x.txt')") shouldBe true
    deniedB(
      "SELECT * FROM read_parquet('abfss://lakebucket@acct.dfs.core.windows.net/x.parquet')"
    ) shouldBe true
  }

  it should "not be evadable with globs above the dataPath prefix" in {
    deniedB("SELECT * FROM read_parquet('s3://lakebucket/*/main/*/*.parquet')") shouldBe true
    deniedB("SELECT * FROM read_parquet('s3://lakebucket/**/*.parquet')") shouldBe true
  }

  it should "deny a list literal containing a DuckLake-bucket element" in {
    deniedB(
      "SELECT * FROM read_parquet(['s3://staging/a.parquet', 's3://lakebucket/b.parquet'])"
    ) shouldBe true
    deniedB("SELECT * FROM read_parquet(['s3://staging/a.parquet'])") shouldBe false
  }

  it should "deny FROM-position replacement scans on a DuckLake bucket" in {
    deniedB("SELECT * FROM 's3://lakebucket/x.parquet'") shouldBe true
    deniedB("SELECT * FROM 's3://staging/x.parquet'") shouldBe false
  }

  it should "deny COPY in both directions on a DuckLake bucket" in {
    deniedB("COPY t TO 's3://lakebucket/main/t/f.parquet'") shouldBe true
    deniedB("COPY t FROM 's3://lakebucket/x.csv'") shouldBe true
    deniedB("COPY t TO 's3://staging/x.csv' (DELIMITER '|')") shouldBe false
    deniedB("COPY a FROM b") shouldBe false
  }

  it should "deny https path-style and virtual-host forms of a DuckLake bucket" in {
    deniedB(
      "SELECT * FROM read_parquet('https://minio.local:9000/lakebucket/x.parquet')"
    ) shouldBe true
    deniedB(
      "SELECT * FROM read_parquet('https://lakebucket.s3.amazonaws.com/x.parquet')"
    ) shouldBe true
    deniedB(
      "SELECT * FROM read_parquet('https://minio.local:9000/staging/x.parquet')"
    ) shouldBe false
  }

  it should "admit other buckets and everything with an empty deny-set" in {
    deniedB("SELECT * FROM read_csv('s3://staging/x.csv', header = true)") shouldBe false
    deniedB("SELECT * FROM read_parquet('gs://other/x.parquet')") shouldBe false
    denied("SELECT * FROM read_parquet('s3://lakebucket/x.parquet')") shouldBe false
    denied("COPY t TO 's3://lakebucket/x.parquet'") shouldBe false
  }

  it should "name the bucket in the denial reason" in {
    LockdownScreen
      .screen("COPY t TO 's3://lakebucket/x.parquet'", Set("lakebucket"))
      .get should include("'lakebucket'")
    LockdownScreen
      .screen("SELECT * FROM read_parquet('s3://lakebucket/x.parquet')", Set("lakebucket"))
      .get should include("'lakebucket'")
  }

  // ---- interior invisible trivia cannot hide a local-path read either ----
  //
  // Java's default `\s` (used by every regex in this file) is `[ \t\n\x0B\f\r]`. It does not
  // match NBSP (U+00A0), figure space (U+2007), narrow no-break space (U+202F), or the Cf
  // characters (zero-width space U+200B, word joiner U+2060, BOM U+FEFF). DuckDB's own parser
  // front end substitutes ASCII spaces for its whole Unicode space/format set before parsing,
  // so `SELECT * FROM<NBSP>'/etc/passwd.parquet'` is an ordinary, executable replacement scan
  // to DuckDB -- verified against a real DuckDB 1.5.4, both this and the read_parquet form
  // below return the target file's rows -- while the un-normalized regex above saw no
  // separator between `from` and the quote at all and let it straight through. Every character
  // below is written as a literal `\uXXXX` escape, never a raw invisible byte (see the
  // byte-hygiene test at the end of this file).
  "interior trivia" should "not hide a bare-path FROM behind a non-breaking space" in {
    denied("SELECT * FROM\u00A0'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a figure space (U+2007)" in {
    denied("SELECT * FROM\u2007'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a narrow no-break space (U+202F)" in {
    denied("SELECT * FROM\u202F'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a zero-width space (U+200B)" in {
    denied("SELECT * FROM\u200B'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a word joiner (U+2060)" in {
    denied("SELECT * FROM\u2060'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a bare-path FROM behind a BOM (U+FEFF)" in {
    denied("SELECT * FROM\uFEFF'/etc/passwd.parquet'") shouldBe true
  }

  it should "not hide a denied function call behind interior trivia" in {
    denied("SELECT * FROM read_parquet\u00A0('/etc/x.parquet')") shouldBe true
    denied("SELECT * FROM read_csv\u00A0('/etc/y.csv')") shouldBe true
  }

  it should "not hide a COPY path literal behind interior trivia" in {
    denied("COPY t TO\u00A0'/tmp/out.csv'") shouldBe true
  }

  it should "still admit a remote literal with interior trivia (over-denial guard)" in {
    // The remote exemption must survive normalization: a legitimate object-store literal
    // separated from FROM by an invisible character is not local-path evidence.
    denied("SELECT * FROM\u00A0's3://bucket/x.parquet'") shouldBe false
  }

  it should "leave an ordinary statement with no trivia behaving exactly as before" in {
    denied("SELECT * FROM read_parquet('s3://bucket/k.parquet')") shouldBe false
    denied("SELECT * FROM '/etc/passwd.parquet'") shouldBe true
    denied("ATTACH 'x' AS y") shouldBe true
    denied("SELECT 1") shouldBe false
  }

  // ---- escape hygiene of this file's own invisible-character test literals ----
  //
  // Every trivia character exercised above must be a literal `\uXXXX` escape, never a raw
  // invisible byte pasted into the source: an editor or an "helpful" formatting pass can
  // silently decode `\u00A0` back into a raw NBSP, at which point this file still compiles and
  // every assertion above still passes (a `String` built from the escape and one built from the
  // raw byte are identical at runtime -- that's the whole point of an escape), while the next
  // reader sees what looks like ordinary blank space around a keyword with no way to tell the
  // test asserts anything about trivia at all. This test reads this very source file back and
  // fails if any codepoint above ASCII (U+007F) appears anywhere in it.
  it should "carry no raw non-ASCII codepoints in its own source file" in {
    val path = "src/test/scala/ai/starlake/quack/edge/sql/LockdownScreenSpec.scala"
    val file = new java.io.File(path)
    withClue(s"expected to find $path relative to the working directory ${file.getAbsolutePath}") {
      file.exists shouldBe true
    }
    val src = scala.io.Source.fromFile(file, "UTF-8")
    try
      val offenders = src.mkString.zipWithIndex.filter { case (c, _) => c.toInt > 0x7f }
      withClue(s"found non-ASCII codepoints at offsets ${offenders.map(_._2).mkString(", ")}: ") {
        offenders shouldBe empty
      }
    finally src.close()
  }
