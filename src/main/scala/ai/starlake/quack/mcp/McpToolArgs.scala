package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.ErrorResponse
import io.circe.{Json, JsonObject}
import sttp.model.StatusCode

/** Argument parsing, schema literals, and the tenant-inference rule shared by both tool tiers. */
private[mcp] object McpToolArgs:

  def str(args: JsonObject, name: String): Option[String] =
    args(name).flatMap(_.asString).map(_.trim).filter(_.nonEmpty)

  def int(args: JsonObject, name: String): Option[Int] =
    args(name).flatMap(_.asNumber).flatMap(_.toInt)

  def long(args: JsonObject, name: String): Option[Long] =
    args(name).flatMap(_.asNumber).flatMap(_.toLong)

  def bool(args: JsonObject, name: String): Option[Boolean] =
    args(name).flatMap(_.asBoolean)

  def required(args: JsonObject, name: String): Either[String, String] =
    str(args, name).toRight(s"the '$name' argument is required")

  def strProp(description: String): Json =
    Json.obj("type" -> Json.fromString("string"), "description" -> Json.fromString(description))

  def intProp(description: String): Json =
    Json.obj("type" -> Json.fromString("integer"), "description" -> Json.fromString(description))

  def boolProp(description: String): Json =
    Json.obj("type" -> Json.fromString("boolean"), "description" -> Json.fromString(description))

  def objectSchema(required: List[String], props: (String, Json)*): Json =
    Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" -> Json.obj(props*),
      "required"   -> Json.arr(required.map(Json.fromString)*)
    )

  /** The tenant a tool call acts in. A tenant-scoped PAT owns exactly one answer (an explicit
    * differing `tenant` argument is an error, never silently ignored); superuser PATs and the
    * static key must say which tenant they mean.
    */
  def tenantOf(principal: McpPrincipal, args: JsonObject): Either[String, String] =
    val explicit = str(args, "tenant")
    principal match
      case McpPrincipal.Pat(p) if p.user.tenant.isDefined =>
        val own = p.user.tenant.get
        explicit match
          case Some(t) if t != own =>
            Left(
              s"your token is scoped to tenant '$own'; omit the 'tenant' argument or pass '$own'"
            )
          case _ => Right(own)
      case _ =>
        explicit.toRight(
          "the 'tenant' argument is required: a superuser credential is not bound to a tenant"
        )

  /** REST-handler bridge: the handlers' (code, ErrorResponse) rejections become tool-level error
    * text, keeping the stable `error` code in front so agents can branch on it.
    */
  def bridge[A](res: Either[(StatusCode, ErrorResponse), A]): Either[String, A] =
    res.left.map((_, e) => s"${e.error}: ${e.message}")

  /** The shared `tenant` schema property: PATs infer it, superuser credentials pass it. */
  val tenantProp: (String, Json) =
    "tenant" -> strProp("Tenant id; only for superuser credentials (PATs infer it).")
