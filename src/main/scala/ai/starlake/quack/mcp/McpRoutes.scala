package ai.starlake.quack.mcp

import ai.starlake.quack.{McpConfig, RestLimitsConfig}
import ai.starlake.quack.edge.BoundedBody
import ai.starlake.quack.ondemand.auth.PatPrincipal
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.{Json, JsonObject}
import io.circe.parser.decode
import org.http4s.{HttpRoutes, MediaType, Response, Status}
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

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
  * Auth first, before the body is even read: `Authorization: Bearer <t>` where `t` is a live
  * personal access token, and nothing else. A session JWT fails by construction (no `qod_pat_`
  * prefix, so the PAT lookup refuses it), which is the design: `/mcp` never admits session
  * credentials and never runs unauthenticated. A PAT costs database lookups, so it resolves on
  * `IO.blocking`, off the compute threads.
  *
  * Only a caller whose PAT resolved gets its body read, and the read is bounded (`BoundedBody`, the
  * REST guard's own): past `limits.maxBodyBytes` is `413`, not whole within `limits.bodyTimeoutSec`
  * is `408` (both say `Connection: close`, as the rest of the body stays unread, and so does the
  * `401` of a PAT that did not resolve), and JSON nested too deep is a JSON-RPC parse error (see
  * `BoundedBody.MaxNesting`). The bytes are read as UTF-8. `limits` has no default, so a wiring
  * cannot forget it.
  *
  * `resolvePat` is the narrow seam (`PatAuthenticator.resolve` in Main) so protocol tests need no
  * store.
  */
final class McpRoutes(
    cfg: McpConfig,
    resolvePat: String => Option[PatPrincipal],
    tools: List[McpToolDef],
    serverVersion: String,
    limits: RestLimitsConfig,
    /** A tool's error as a caller who is not a superuser reads it (`TenantDb.textShownToTenant`).
      */
    textForTenant: String => String
) extends LazyLogging:

  import McpJsonRpc._

  private val byName: Map[String, McpToolDef] = tools.map(t => t.name -> t).toMap

  private def authenticate(header: Option[String]): Option[McpPrincipal] =
    header.flatMap { h =>
      if !h.startsWith("Bearer ") then None
      else
        val token = h.stripPrefix("Bearer ")
        resolvePat(token).map(p => new McpPrincipal.Pat(p, token))
    }

  private def jsonResponse(status: Status, body: Json): Response[IO] =
    Response[IO](status)
      .withEntity(body.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" =>
      // A PAT costs a database lookup, so it resolves off the compute threads.
      IO.blocking(authenticate(req.headers.get(CIString("Authorization")).map(_.head.value)))
        .flatMap {
          case None =>
            IO.pure(
              jsonResponse(
                Status.Unauthorized,
                Json.obj("error" -> Json.fromString("unauthorized"))
              ).putHeaders(org.http4s.headers.Connection.close)
            )
          case Some(principal) =>
            BoundedBody.read(req, limits.maxBodyBytes, limits.bodyTimeoutSec.seconds).flatMap {
              case BoundedBody.Outcome.Read(bytes) =>
                decode[Request](new String(bytes, UTF_8)) match
                  case Left(_) =>
                    IO.pure(
                      jsonResponse(Status.Ok, err(None, ParseError, "malformed JSON-RPC request"))
                    )
                  case Right(rpc) => dispatch(principal, rpc)
              case BoundedBody.Outcome.TooDeep =>
                IO.pure(jsonResponse(Status.Ok, err(None, ParseError, "request nests too deeply")))
              case BoundedBody.Outcome.TooLarge =>
                IO.pure(
                  jsonResponse(
                    Status.PayloadTooLarge,
                    Json.obj("error" -> Json.fromString("payload_too_large"))
                  ).putHeaders(org.http4s.headers.Connection.close)
                )
              case BoundedBody.Outcome.TimedOut =>
                IO.pure(
                  jsonResponse(
                    Status.RequestTimeout,
                    Json.obj("error" -> Json.fromString("request_timeout"))
                  ).putHeaders(org.http4s.headers.Connection.close)
                )
            }
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
        val visible = tools.filter(t =>
          (!t.adminOnly || principal.isAdmin) && principal.restriction.allowsTool(t.name)
        )
        val listed = visible.map { t =>
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
          // adminOnly AND the token allowlist are re-checked HERE, not just at listing
          // time: the server never trusts that a client only calls the tools it was
          // shown. Unauthorized and unknown collapse to one answer (no tier-probing
          // oracle) -- a tool outside the allowlist falls into the same `case _` as a
          // name that does not exist at all.
          case Some(tool)
              if (!tool.adminOnly || principal.isAdmin)
                && principal.restriction.allowsTool(tool.name) =>
            tool
              .run(principal, args)
              .map {
                case Right(json) => toolResult(rpc.id, json.noSpaces, isError = false)
                case Left(msg)   =>
                  val read = if principal.superuser then msg else textForTenant(msg)
                  if read != msg then logger.error(s"mcp tools/call $name: $msg")
                  toolResult(rpc.id, read, isError = true)
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
