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

  /** A JSON array of non-blank strings. Absent or JSON null is `Right(None)`; any other shape (a
    * bare string, an object, a non-string or blank element) is an error, never "omitted": a
    * malformed restricting list must not fall back to a permissive default.
    */
  def strList(
      args: JsonObject,
      name: String,
      what: String
  ): Either[String, Option[List[String]]] =
    val err = s"the '$name' argument must be an array of $what"
    args(name) match
      case None                => Right(None)
      case Some(j) if j.isNull => Right(None)
      case Some(j)             =>
        j.asArray match
          case None      => Left(err)
          case Some(arr) =>
            val items = arr.toList.map(_.asString.map(_.trim).filter(_.nonEmpty))
            if items.exists(_.isEmpty) then Left(err) else Right(Some(items.flatten))

  def required(args: JsonObject, name: String): Either[String, String] =
    str(args, name).toRight(s"the '$name' argument is required")

  def strProp(description: String): Json =
    Json.obj("type" -> Json.fromString("string"), "description" -> Json.fromString(description))

  def intProp(description: String): Json =
    Json.obj("type" -> Json.fromString("integer"), "description" -> Json.fromString(description))

  def strListProp(description: String): Json =
    Json.obj(
      "type"        -> Json.fromString("array"),
      "items"       -> Json.obj("type" -> Json.fromString("string")),
      "description" -> Json.fromString(description)
    )

  def boolProp(description: String): Json =
    Json.obj("type" -> Json.fromString("boolean"), "description" -> Json.fromString(description))

  def objectSchema(required: List[String], props: (String, Json)*): Json =
    Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" -> Json.obj(props*),
      "required"   -> Json.arr(required.map(Json.fromString)*)
    )

  /** The tenant a tool call acts in. A tenant-scoped PAT owns exactly one answer (an explicit
    * differing `tenant` argument is an error, never silently ignored); a superuser PAT is not bound
    * to a tenant and must say which one it means.
    */
  def tenantOf(principal: McpPrincipal, args: JsonObject): Either[String, String] =
    val explicit = str(args, "tenant")
    principal match
      case McpPrincipal.Pat(p) =>
        p.user.tenant match
          case Some(own) =>
            explicit match
              case Some(t) if t != own =>
                Left(
                  s"your token is scoped to tenant '$own'; omit the 'tenant' argument or pass '$own'"
                )
              case _ => Right(own)
          case None =>
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

  /** Read a {"k":"v"} object argument as Some(map); absent or non-object = None. */
  def mapArgOpt(args: JsonObject, name: String): Option[Map[String, String]] =
    args(name)
      .flatMap(_.asObject)
      .map(_.toMap.flatMap((k, v) => v.asString.map(k -> _)))

  /** Read a {"k":"v"} object argument as Map[String,String]; absent = empty. */
  def mapArg(args: JsonObject, name: String): Map[String, String] =
    mapArgOpt(args, name).getOrElse(Map.empty)

  /** Read a ["a","b"] array argument as Some(set); absent or non-array = None. */
  def strSet(args: JsonObject, name: String): Option[Set[String]] =
    args(name).flatMap(_.asArray).map(_.flatMap(_.asString).toSet)

  def double(args: JsonObject, name: String): Option[Double] =
    args(name).flatMap(_.asNumber).map(_.toDouble)

  def objProp(description: String): Json =
    Json.obj(
      "type"        -> Json.fromString("object"),
      "description" -> Json.fromString(description)
    )

  def arrayProp(description: String): Json =
    Json.obj(
      "type"        -> Json.fromString("array"),
      "items"       -> Json.obj("type" -> Json.fromString("string")),
      "description" -> Json.fromString(description)
    )
