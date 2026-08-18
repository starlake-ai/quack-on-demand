package ai.starlake.quack.mcp

import ai.starlake.quack.McpConfig
import ai.starlake.quack.ondemand.auth.PatPrincipal
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.{Json, JsonObject}
import io.circe.parser.decode
import org.http4s.{HttpRoutes, MediaType, Response, Status}
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** One MCP tool: metadata for `tools/list` plus its executable body. `run`'s `Left(message)`
  * becomes a `tools/call` result with `isError: true` (a normal result the agent acts on, per the
  * MCP error-layering contract), never a JSON-RPC error.
  */
final case class McpToolDef(
    name: String,
    description: String,
    inputSchema: Json,
    adminOnly: Boolean,
    run: (McpPrincipal, JsonObject) => IO[Either[String, Json]]
)

/** The MCP Streamable HTTP endpoint: `POST /mcp`, one JSON-RPC message per request, plain JSON
  * response, no SSE and no session id, so any HA replica answers any request.
  *
  * Auth first, before the body is even parsed: `Authorization: Bearer <t>` where `t` is the static
  * API key (constant-time compare, only when configured non-empty) or a live PAT. A session JWT
  * fails both arms by construction (wrong prefix for the PAT arm, no constant-time match for the
  * static arm), which is the design: `/mcp` never admits session credentials and never runs
  * unauthenticated -- there is no open mode here and never was.
  *
  * `resolvePat` is the narrow seam (`PatAuthenticator.resolve` in Main) so protocol tests need no
  * store.
  */
final class McpRoutes(
    cfg: McpConfig,
    staticKey: Option[String],
    resolvePat: String => Option[PatPrincipal],
    tools: List[McpToolDef],
    serverVersion: String
) extends LazyLogging:

  import McpJsonRpc._

  private val byName: Map[String, McpToolDef] = tools.map(t => t.name -> t).toMap

  private def constantTimeEq(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(
      a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

  private def authenticate(header: Option[String]): Option[McpPrincipal] =
    header.flatMap { h =>
      if !h.startsWith("Bearer ") then None
      else
        val token = h.stripPrefix("Bearer ")
        if token.nonEmpty && staticKey.exists(k => constantTimeEq(token, k)) then
          Some(McpPrincipal.StaticKey)
        else resolvePat(token).map(p => new McpPrincipal.Pat(p, token))
    }

  private def jsonResponse(status: Status, body: Json): Response[IO] =
    Response[IO](status)
      .withEntity(body.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" =>
      authenticate(req.headers.get(CIString("Authorization")).map(_.head.value)) match
        case None =>
          IO.pure(
            jsonResponse(Status.Unauthorized, Json.obj("error" -> Json.fromString("unauthorized")))
          )
        case Some(principal) =>
          req.as[String].flatMap { raw =>
            decode[Request](raw) match
              case Left(_) =>
                IO.pure(
                  jsonResponse(Status.Ok, err(None, ParseError, "malformed JSON-RPC request"))
                )
              case Right(rpc) => dispatch(principal, rpc)
          }

    case GET -> Root / "mcp" =>
      IO.pure(Response[IO](Status.MethodNotAllowed))
  }

  private def dispatch(principal: McpPrincipal, rpc: Request): IO[Response[IO]] =
    rpc.method match
      case "initialize" =>
        IO.pure(
          jsonResponse(
            Status.Ok,
            ok(
              rpc.id,
              Json.obj(
                "protocolVersion" -> Json.fromString("2025-06-18"),
                "capabilities"    -> Json.obj("tools" -> Json.obj()),
                "serverInfo"      -> Json.obj(
                  "name"    -> Json.fromString("quack-on-demand"),
                  "version" -> Json.fromString(serverVersion)
                )
              )
            )
          )
        )

      case "notifications/initialized" =>
        IO.pure(Response[IO](Status.Accepted))

      case "ping" =>
        IO.pure(jsonResponse(Status.Ok, ok(rpc.id, Json.obj())))

      case "tools/list" =>
        val visible = tools.filter(t => !t.adminOnly || principal.isAdmin)
        val listed  = visible.map { t =>
          Json.obj(
            "name"        -> Json.fromString(t.name),
            "description" -> Json.fromString(t.description),
            "inputSchema" -> t.inputSchema
          )
        }
        IO.pure(jsonResponse(Status.Ok, ok(rpc.id, Json.obj("tools" -> Json.arr(listed*)))))

      case "tools/call" =>
        val params = rpc.params.getOrElse(JsonObject.empty)
        val name   = params("name").flatMap(_.asString).getOrElse("")
        val args   = params("arguments").flatMap(_.asObject).getOrElse(JsonObject.empty)
        byName.get(name) match
          // adminOnly is re-checked HERE, not just at listing time: the server never
          // trusts that a client only calls the tools it was shown. Unauthorized and
          // unknown collapse to one answer (no tier-probing oracle).
          case Some(tool) if !tool.adminOnly || principal.isAdmin =>
            tool
              .run(principal, args)
              .map {
                case Right(json) => toolResult(rpc.id, json.noSpaces, isError = false)
                case Left(msg)   => toolResult(rpc.id, msg, isError = true)
              }
              .handleError { t =>
                val correlationId = java.util.UUID.randomUUID().toString.take(8)
                logger.error(s"mcp tools/call $name failed [$correlationId]", t)
                jsonResponse(
                  Status.Ok,
                  err(rpc.id, InternalError, s"internal error (correlation id $correlationId)")
                )
              }
          case _ =>
            IO.pure(jsonResponse(Status.Ok, err(rpc.id, InvalidParams, s"unknown tool: '$name'")))

      case other =>
        IO.pure(jsonResponse(Status.Ok, err(rpc.id, MethodNotFound, s"method not found: '$other'")))

  private def toolResult(id: Option[Json], text: String, isError: Boolean): Response[IO] =
    jsonResponse(
      Status.Ok,
      ok(
        id,
        Json.obj(
          "content" -> Json.arr(
            Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text))
          ),
          "isError" -> Json.fromBoolean(isError)
        )
      )
    )
