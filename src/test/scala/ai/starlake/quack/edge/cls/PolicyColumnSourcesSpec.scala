package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.PoolKey
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

class PolicyColumnSourcesSpec extends AnyFlatSpec with Matchers:

  private val pool = PoolKey("acme", "fed", "bi")

  private def describeRows(names: String*): List[List[Json]] =
    names.toList.map(n =>
      List(Json.fromString(n), Json.fromString("VARCHAR"), Json.fromString("YES"))
    )

  private final class Fixture(
      lake: Map[(String, String, String), List[String]] = Map.empty,
      answer: IO[Either[RouterFailure, List[List[Json]]]] =
        IO.pure(Right(describeRows("id", "ssn"))),
      timeout: FiniteDuration = 5.seconds,
      lakeFails: Boolean = false
  ):
    val sent    = ListBuffer.empty[(PoolKey, String)]
    val sources = new PolicyColumnSources(
      duckLake = (_, c, s, t) =>
        if lakeFails then IO.raiseError(new RuntimeException("catalog down"))
        else IO.pure(lake.getOrElse((c, s, t), Nil)),
      node = (k, sql) => IO(sent += ((k, sql))) *> answer,
      nodeTimeout = timeout
    )
    def fetch(p: Option[PoolKey], tenant: String = "acme"): List[String] =
      sources.fetch(tenant, p, "pgfed", "e2e", "orders").unsafeRunSync()

  "fetch" should "read a DuckLake table from its catalog and never ask a node" in {
    val f = new Fixture(lake = Map(("pgfed", "e2e", "orders") -> List("a", "b")))
    f.fetch(Some(pool)) shouldBe List("a", "b")
    f.sent shouldBe empty
  }

  it should "describe any other table on a node of the statement's pool" in {
    val f = new Fixture
    f.fetch(Some(pool)) shouldBe List("id", "ssn")
    f.sent.toList shouldBe List(pool -> "DESCRIBE \"pgfed\".\"e2e\".\"orders\"")
  }

  it should "ask the node when the DuckLake read fails" in {
    val f = new Fixture(lakeFails = true)
    f.fetch(Some(pool)) shouldBe List("id", "ssn")
  }

  it should "list nothing with no pool, or a pool of another tenant" in {
    val f = new Fixture
    f.fetch(None) shouldBe Nil
    f.fetch(Some(pool), tenant = "globex") shouldBe Nil
    f.sent shouldBe empty
  }

  it should "list nothing when the node refuses, fails or does not answer in time" in {
    new Fixture(answer = IO.pure(Left(RouterFailure.Unavailable("x"))))
      .fetch(Some(pool)) shouldBe Nil
    new Fixture(answer = IO.raiseError(new RuntimeException("boom"))).fetch(Some(pool)) shouldBe Nil
    new Fixture(answer = IO.never, timeout = 50.millis).fetch(Some(pool)) shouldBe Nil
  }

  "describeSql" should "quote every part so no name can end it, and skip empty ones" in {
    PolicyColumnSources.describeSql("c", "s", "t") shouldBe Some("DESCRIBE \"c\".\"s\".\"t\"")
    PolicyColumnSources.describeSql("", "s", "t") shouldBe Some("DESCRIBE \"s\".\"t\"")
    PolicyColumnSources.describeSql("c", "s", "a\"; DROP TABLE x; --") shouldBe
      Some("DESCRIBE \"c\".\"s\".\"a\"\"; DROP TABLE x; --\"")
    PolicyColumnSources.describeSql("c", "s", "") shouldBe None
  }

  "columnNames" should "take each row's first column, dropping anything not a name" in {
    PolicyColumnSources.columnNames(describeRows("a", "b")) shouldBe List("a", "b")
    PolicyColumnSources.columnNames(List(List(Json.Null), Nil)) shouldBe Nil
  }
