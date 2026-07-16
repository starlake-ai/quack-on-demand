package ai.starlake.quack.edge.auth

import scala.util.matching.Regex

/** Regex JSON string-field extraction, shared by the auth flows that read one or two fields out of
  * a provider response (token endpoints, service-account keys, Directory API pages) without pulling
  * a full JSON parse into the hot path.
  *
  * Semantics (identical to the four historical private copies this replaced): the field name
  * matches at ANY depth, only string values match, and the captured value is returned RAW - no
  * unescaping, and values containing an escaped quote are truncated at it. Callers that need real
  * JSON semantics should parse with circe instead.
  */
private[auth] object JsonField:

  private def pattern(field: String): Regex =
    s""""${Regex.quote(field)}"\\s*:\\s*"([^"]+)"""".r

  /** First string value bound to `field`, at any depth. */
  def first(json: String, field: String): Option[String] =
    pattern(field).findFirstMatchIn(json).map(_.group(1))

  /** Every string value bound to `field`, in document order. */
  def all(json: String, field: String): List[String] =
    pattern(field).findAllMatchIn(json).map(_.group(1)).toList
