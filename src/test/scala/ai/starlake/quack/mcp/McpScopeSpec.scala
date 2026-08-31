package ai.starlake.quack.mcp

import ai.starlake.quack.McpConfig
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.RbacUser
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.http4s.{Method, Request, Uri}
import org.http4s.headers.Authorization
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

/** Tool-allowlist enforcement on the MCP route. Synthetic tools, no supervisor, no Postgres. */
class McpScopeSpec extends AnyFlatSpec with Matchers:

  private val token = "qod_pat_scoped"

  private def tool(n: String) = McpToolDef(
    name = n,
    description = n,
    inputSchema = Json.obj("type" -> Json.fromString("object")),
    adminOnly = false,
    run = (_, _) => IO.pure(Right(Json.obj("ok" -> Json.True)))
  )

  private def routesFor(restriction: TokenRestriction): McpRoutes =
    val principal = PatPrincipal(
      user = RbacUser(id = "u1", tenant = Some("acme"), username = "alice", role = "user"),
      patId = "pat-1",
      scope = SessionScope(superuser = false, manageableTenants = Set.empty),
      isAdmin = false,
      restriction = restriction
    )
    new McpRoutes(
      cfg = McpConfig(),
      staticKey = None,
      resolvePat = t => if t == token then Some(principal) else None,
      tools = List(tool("run_sql"), tool("list_databases")),
      serverVersion = "test"
    )

  private def rpc(r: McpRoutes, body: String): String =
    val req = Request[IO](Method.POST, Uri.unsafeFromString("/mcp"))
      .withEntity(body)
      .putHeaders(org.http4s.Header.Raw(CIString("Authorization"), s"Bearer $token"))
    r.routes.orNotFound.run(req).flatMap(_.as[String]).unsafeRunSync()

  private def toolNames(body: String): List[String] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("result").downField("tools").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("name").toOption)

  private def resultText(body: String): String =
    parse(body).toOption
      .flatMap(
        _.hcursor
          .downField("result")
          .downField("content")
          .downArray
          .get[String]("text")
          .toOption
      )
      .orElse(
        parse(body).toOption.flatMap(_.hcursor.downField("error").get[String]("message").toOption)
      )
      .getOrElse("")

  private val listReq               = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
  private def callReq(name: String) =
    s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":{}}}"""

  "tools/list" should "hide a tool outside the allowlist" in {
    val names = toolNames(
      rpc(
        routesFor(TokenRestriction.Unrestricted.copy(tools = Some(Set("list_databases")))),
        listReq
      )
    )
    names should contain("list_databases")
    names should not contain "run_sql"
  }

  it should "list everything when the axis is unrestricted" in {
    toolNames(rpc(routesFor(TokenRestriction.Unrestricted), listReq)) should
      contain allOf ("run_sql", "list_databases")
  }

  it should "list nothing when the allowlist is empty" in {
    toolNames(
      rpc(routesFor(TokenRestriction.Unrestricted.copy(tools = Some(Set.empty))), listReq)
    ) shouldBe Nil
  }

  // Forbidden and unknown must stay indistinguishable so a client cannot probe which
  // tools exist above its tier. This mirrors the adminOnly arm that already exists.
  "tools/call" should "refuse a hidden tool with the same shape as an unknown tool" in {
    val scoped = routesFor(TokenRestriction.Unrestricted.copy(tools = Some(Set("list_databases"))))
    resultText(rpc(scoped, callReq("run_sql"))) shouldBe "unknown tool: 'run_sql'"
    resultText(rpc(scoped, callReq("nope"))) shouldBe "unknown tool: 'nope'"
  }

  it should "still run a tool inside the allowlist" in {
    val scoped = routesFor(TokenRestriction.Unrestricted.copy(tools = Some(Set("run_sql"))))
    resultText(rpc(scoped, callReq("run_sql"))) should include("ok")
  }
