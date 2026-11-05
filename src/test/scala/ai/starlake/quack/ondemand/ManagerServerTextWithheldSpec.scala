package ai.starlake.quack.ondemand

import ai.starlake.quack.model.TenantDb
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.{Response, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What a caller who is not a superuser reads of an error naming QoD's Postgres server. */
class ManagerServerTextWithheldSpec
    extends AnyFlatSpec
    with Matchers
    with ai.starlake.quack.security.SecurityHttpHelpers:

  private val defaults = Map("pgHost" -> "pg.internal", "pgPort" -> "5432", "pgUser" -> "qod")
  private val named    = "Connection to pg.internal:5432 refused"

  private def answer(status: Status, body: String) =
    val out = ManagerServer
      .withServerTextWithheld(TenantDb.textShownToTenant(_, defaults), "/api/x")(
        Response[IO](status).withEntity(body)
      )
      .unsafeRunSync()
    (out.status, out.as[String].unsafeRunSync(), out.contentLength)

  private def error(code: String, message: String) =
    io.circe.Json
      .obj(
        "error"   -> io.circe.Json.fromString(code),
        "message" -> io.circe.Json.fromString(message)
      )
      .noSpaces

  "A server failure" should "keep its code and lose a text naming QoD's Postgres" in
    List(Status.InternalServerError, Status.BadGateway, Status.Conflict).foreach { status =>
      val (code, body, length) = answer(status, error("internal", named))
      code shouldBe status
      body shouldBe error("internal", TenantDb.ServerTextWithheld)
      length shouldBe Some(body.getBytes("UTF-8").length.toLong)
    }

  it should "stay as it is when it names nothing of it" in {
    val body = error("internal", "the store is down")
    answer(Status.InternalServerError, body)._2 shouldBe body
  }

  it should "lose a text that is no JSON" in {
    answer(Status.BadGateway, named)._2 shouldBe error("internal", TenantDb.ServerTextWithheld)
  }

  "A database create failing on QoD's Postgres" should "tell only a superuser why" in {
    import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures}
    val failing = new ai.starlake.quack.ondemand.state.DbAdmin:
      def createDatabase(name: String): Either[String, Unit]   = Left(named)
      def dropDatabase(name: String): Either[String, Unit]     = Right(())
      def holdsAnything(name: String): Either[String, Boolean] = Right(false)
    val h = ManagerServerHarness.boot(
      SecurityFixtures.freshStore().store,
      supervisorDefaults = defaults ++ Map(
        "dataPath"   -> "/tmp/qod-text-spec/lake",
        "schemaName" -> "main",
        "dbName"     -> "qod",
        "pgPassword" -> "default-login-sentinel"
      ),
      dbAdmin = failing
    )
    try
      def create(token: String, name: String) = post(
        h.httpClient,
        s"${h.baseUrl}/api/database/create",
        io.circe.Json
          .obj(
            "tenant" -> io.circe.Json.fromString(SecurityFixtures.TenantId),
            "name"   -> io.circe.Json.fromString(name),
            "kind"   -> io.circe.Json.fromString("ducklake")
          )
          .noSpaces,
        apiKey = Some(token)
      )
      val alice = create(
        h.mintToken(
          SecurityFixtures.AliceUsername,
          SecurityFixtures.AlicePassword,
          Some(SecurityFixtures.TenantId)
        ),
        "isofail"
      )
      withClue(alice.body())(alice.statusCode() shouldBe 500)
      alice.body() shouldBe error("internal", TenantDb.ServerTextWithheld)
      val root =
        create(
          h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword),
          "isofail2"
        )
      root.statusCode() shouldBe 500
      root.body() should include("pg.internal")
    finally h.shutdown()
  }

  "A refusal of the request" should "stay as it is, whatever it quotes" in
    List(Status.BadRequest, Status.Forbidden, Status.NotFound).foreach { status =>
      val body = error("invalid", named)
      answer(status, body)._2 shouldBe body
    }

  "A success" should "stay as it is" in {
    answer(Status.Ok, named)._2 shouldBe named
  }
