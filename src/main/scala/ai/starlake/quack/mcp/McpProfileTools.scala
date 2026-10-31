package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{ProfileS3RemoveRequest, UserS3CredentialHandlers}
import ai.starlake.quack.ondemand.api.Dtos.given
import cats.effect.IO
import io.circe.syntax._

/** The caller's own profile over MCP: its S3 credentials, listed and removed (decision 23). Any
  * token (`adminOnly = false`), always the token owner's own row. There is no add: a secret must
  * not pass through an agent's context (`McpCoverageSpec` excludes `POST /api/profile/s3/add`).
  */
final class McpProfileTools(s3: UserS3CredentialHandlers):

  import McpToolArgs._

  def tools: List[McpToolDef] = List(listMyS3CredentialsTool, removeMyS3CredentialTool)

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  private val listMyS3CredentialsTool = McpToolDef(
    name = "list_my_s3_credentials",
    description = "List your own S3 credentials: each scope (s3://bucket/ or a prefix) you reach " +
      "S3 under, its access key id, region and endpoint. The secret and the session token are " +
      "never returned. Add credentials with `qod profile s3 add` or the profile page, never here.",
    inputSchema = objectSchema(required = Nil),
    adminOnly = false,
    run = (principal, _) => s3.listMine(keyOf(principal)).map(res => bridge(res).map(_.asJson))
  )

  private val removeMyS3CredentialTool = McpToolDef(
    name = "remove_my_s3_credential",
    description = "Remove one of your own S3 credentials by id (from list_my_s3_credentials). " +
      "Your statements stop reaching its scope at once.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("The credential id.")
    ),
    adminOnly = false,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          s3.removeMine(ProfileS3RemoveRequest(id), keyOf(principal))
            .map(res => bridge(res).map(_.asJson))
  )
