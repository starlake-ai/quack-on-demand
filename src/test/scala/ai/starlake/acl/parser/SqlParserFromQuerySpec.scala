package ai.starlake.acl.parser

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for the FromQuery shorthand (`FROM t [pipe ops...]`). JSQLParser
  * parses this as a `Select` subclass (`net.sf.jsqlparser.statement.piped.FromQuery`),
  * but the visitor's legacy match arms only covered PlainSelect / SetOperationList /
  * Values / LateralSubSelect / ParenthesedSelect, so FromQuery used to silently
  * drop all table refs and the validator admitted the query unconditionally.
  *
  * See project memory `project-dml-acl-unenforced` for the parallel DML gap that
  * was fixed in the same wave of parser work. */
class SqlParserFromQuerySpec extends AnyFlatSpec with Matchers:

  private val config = Config.forGeneric("testdb", "public")

  private def heads(sql: String): Set[(String, Verb)] =
    SqlParser.extract(sql, config).statements.headOption match
      case Some(StatementResult.Extracted(_, _, accesses, _)) =>
        accesses.map(a => a.table.canonical -> a.verb)
      case other =>
        fail(s"expected Extracted, got $other")

  "SqlParser FROM-first shorthand" should "extract the single source table as Read" in:
    heads("FROM t") shouldBe Set("testdb.public.t" -> Verb.Read)

  it should "extract a qualified FROM target" in:
    heads("FROM acme.public.financials") shouldBe Set("acme.public.financials" -> Verb.Read)

  it should "walk JOIN sources off the FromQuery" in:
    heads("FROM t JOIN u ON t.id = u.id") shouldBe Set(
      "testdb.public.t" -> Verb.Read,
      "testdb.public.u" -> Verb.Read
    )