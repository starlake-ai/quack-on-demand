package ai.starlake.quack.edge.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The statement splitter and the setting reader the kept screens share ([[StatementHead]]). */
class StatementHeadSpec extends AnyFlatSpec with Matchers:

  "split" should "not split on a semicolon inside a dollar-quoted string" in {
    StatementHead.split("SELECT $$a; b$$; SELECT 2") shouldBe List("SELECT $$a; b$$", " SELECT 2")
  }

  it should "split after an identifier ending in a dollar-tag shape, as DuckDB does" in {
    // DuckDB 2.0: `x$a$` is ONE identifier (identifiers continue through `$`), so the `;` after it
    // is a statement boundary and no dollar-quoted string opens there.
    StatementHead.split("SELECT 1 AS x$a$; ATTACH 'x' AS q; SELECT 2 AS y$a$").length shouldBe 3
  }

  it should "drop blank fragments and keep a comment-only one" in {
    StatementHead.split("SELECT 1;;  ;") shouldBe List("SELECT 1")
    StatementHead.split("USE x; -- done") shouldBe List("USE x", " -- done")
  }

  it should "keep a quoted or commented semicolon inside its statement" in {
    StatementHead.split("SELECT 'a;b'; SELECT \"c;d\" FROM t /* ; */") shouldBe
      List("SELECT 'a;b'", " SELECT \"c;d\" FROM t /* ; */")
  }

  "settingAt" should "read the setting a SET, RESET or PRAGMA names, and its token" in {
    def at(sql: String) = StatementHead.settingAt(StatementHead.of(sql))
    at("SET SESSION search_path = 'x'") shouldBe Some(("search_path", 2))
    at("SET\"Schema\"='x'") shouldBe Some(("schema", 1))
    at("RESET ALL") shouldBe Some(("all", 1))
    at("PRAGMA threads = 4") shouldBe Some(("threads", 1))
    at("EXPLAIN ANALYZE SET threads = 1") shouldBe Some(("threads", 3))
    // a scope word with no name after it is the name
    at("SET global = 1") shouldBe Some(("global", 1))
    // SET VARIABLE reads as the name `variable`: its callers tell it apart
    at("SET VARIABLE x = 1") shouldBe Some(("variable", 1))
  }

  it should "read nothing from another statement" in {
    StatementHead.settingAt(StatementHead.of("SELECT 1")) shouldBe None
    StatementHead.settingAt(StatementHead.of("UPDATE t SET threads = 1")) shouldBe None
  }
