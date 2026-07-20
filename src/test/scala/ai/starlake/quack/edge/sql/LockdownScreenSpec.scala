package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LockdownScreenSpec extends AnyFlatSpec with Matchers:

  private def denied(sql: String): Boolean = LockdownScreen.screen(sql).isDefined

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
    denied("SET memory_limit = '1GB'") shouldBe false
    denied("SET threads = 4") shouldBe false
    denied("SET schema = 'tpch1'") shouldBe false
    denied("PRAGMA table_info('t')") shouldBe false
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
    denied("SET memory_limit = '1GB'") shouldBe false
    denied("PRAGMA table_info('t')") shouldBe false
  }

  "reasons" should "name the blocked construct" in {
    LockdownScreen.screen("ATTACH 'x' AS y").get should include("ATTACH")
    LockdownScreen.screen("SET lock_configuration=false").get should include("lock_configuration")
    LockdownScreen.screen("SELECT read_text('/x')").get should include("read_text")
  }
