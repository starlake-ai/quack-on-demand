package ai.starlake.quack.mcp

import io.circe.{Decoder, Json, JsonObject}

/** The JSON-RPC 2.0 envelope for the MCP Streamable HTTP transport: one request per POST, one
  * response per request. Only the shapes `/mcp` actually speaks live here; transport-level concerns
  * (auth, method dispatch) are [[McpRoutes]]'s.
  */
object McpJsonRpc:

  /** An incoming message. `id = None` marks a notification (no response expected). */
  final case class Request(
      jsonrpc: String,
      id: Option[Json],
      method: String,
      params: Option[JsonObject]
  )

  given Decoder[Request] = Decoder.instance { c =>
    for
      jsonrpc <- c.getOrElse[String]("jsonrpc")("")
      id      <- c.get[Option[Json]]("id")
      method  <- c.get[String]("method")
      params  <- c.get[Option[JsonObject]]("params")
    yield Request(jsonrpc, id, method, params)
  }

  // JSON-RPC 2.0 pre-defined codes; -32602 doubles as the MCP "unknown tool" answer.
  val ParseError: Int     = -32700
  val MethodNotFound: Int = -32601
  val InvalidParams: Int  = -32602
  val InternalError: Int  = -32603

  def ok(id: Option[Json], result: Json): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString("2.0"),
      "id"      -> id.getOrElse(Json.Null),
      "result"  -> result
    )

  def err(id: Option[Json], code: Int, message: String): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString("2.0"),
      "id"      -> id.getOrElse(Json.Null),
      "error"   -> Json.obj(
        "code"    -> Json.fromInt(code),
        "message" -> Json.fromString(message)
      )
    )
