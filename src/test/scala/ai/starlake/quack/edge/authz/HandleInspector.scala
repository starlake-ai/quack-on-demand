package ai.starlake.quack.edge.authz

import io.circe.Json
import io.circe.parser.parse

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Decodes the payload of a session handle, or of the prelude carrying one, for assertions. */
object HandleInspector:

  def payload(handleOrPrelude: String): Json =
    val handle = handleOrPrelude.stripPrefix("CALL system.main.qod_session('").stripSuffix("')")
    val bytes  = Base64.getUrlDecoder.decode(handle.takeWhile(_ != '.'))
    parse(new String(bytes, StandardCharsets.UTF_8)).fold(e => throw e, identity)
