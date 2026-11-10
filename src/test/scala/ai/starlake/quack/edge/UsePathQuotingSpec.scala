package ai.starlake.quack.edge

import ai.starlake.quack.model.{PoolKey, RoleDistribution}
import ai.starlake.quack.ondemand.PoolState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `usePath` is the text of the USE prelude sent before every statement of a pool. A row stored
  * before `defaultSchema` was validated may hold anything, so a name that is not a plain identifier
  * is quoted: it can name a schema, and can never be read as more SQL.
  */
class UsePathQuotingSpec extends AnyFlatSpec with Matchers:

  private def state(defaultSchema: Option[String], alias: String = "sales") =
    PoolState(
      PoolKey("acme", "acme_sales", "bi"),
      Nil,
      RoleDistribution(0, 0, 0),
      Map("dbName" -> alias),
      Map.empty,
      defaultSchema = defaultSchema
    )

  "usePath" should "leave plain names as they are" in:
    FlightSqlRouter.usePath(state(Some("tpch1"))) shouldBe Some("sales.tpch1")

  it should "quote a stored schema that is not a plain identifier" in:
    FlightSqlRouter.usePath(state(Some("sales reporting"))) shouldBe
      Some("sales.\"sales reporting\"")

  it should "double a quote inside a stored schema" in:
    FlightSqlRouter.usePath(state(Some("a\"b"))) shouldBe Some("sales.\"a\"\"b\"")

  it should "quote a catalog alias that is not a plain identifier" in:
    FlightSqlRouter.usePath(state(Some("main"), alias = "my sales")) shouldBe
      Some("\"my sales\".main")
