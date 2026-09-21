package ai.starlake.quack.ondemand.federation.iceberg

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Scrubs credential material out of a DuckDB attach error BEFORE it is stored, not merely before
  * it is rendered.
  *
  * The leak is real and was reproduced against `duckdb v1.5.4` (the pinned node engine), not
  * reasoned about: DuckDB splices an Iceberg REST catalog's HTTP response body VERBATIM into its
  * own error text on the oauth2 token exchange, e.g.
  *
  * {{{
  * Invalid Configuration Error: Could not get token from http://host/v1/oauth/tokens:
  *   HTTP Unauthorized_401 - {"error": {"message": "rejected credential Basic cW9k...MTEx", ...}}
  * }}}
  *
  * DuckDB sends the oauth2 client credentials as `Authorization: Basic
  * base64(client_id:client_secret)`, so a catalog that echoes the header (or the credential) it
  * received puts the PLAINTEXT client secret into the error string. `IcebergAttachVerifier` stores
  * that string in [[AttachStatusRegistry]] and logs it, and Task 8 surfaces it over REST through
  * `NodeInfo.catalogAttachFailures` -- a surface on which `FederatedSecret` values are otherwise
  * always redacted. The catalog is remote and operator-declared, so the echoed text is
  * third-party-controlled; the manager must not launder it into its own API response.
  *
  * Two rules, deliberately narrow so a real diagnostic survives:
  *   1. every known credential VALUE we handed DuckDB is replaced by [[Marker]];
  *   2. every base64-looking run that DECODES to text containing one of those values is replaced
  *      too, which is what catches the `Basic base64(id:secret)` form without having to model
  *      DuckDB's header construction.
  *
  * The client id is deliberately NOT in the credential set: on its own it is not a secret, and
  * keeping it visible is what makes "which principal was rejected" answerable. Rule 2 still redacts
  * the Basic blob that carries it, because that blob also carries the secret.
  *
  * Finally the result is capped at [[MaxErrorChars]]: the spliced body is remote-controlled and
  * unbounded, and one registry entry per (node incarnation, alias) is held in memory and returned
  * on every `GET /api/pool/.../status`.
  */
object AttachErrorRedactor:

  val Marker: String = "[redacted]"

  /** Cap on the stored error text. Generous enough for DuckDB's own message plus a realistic
    * catalog error body; small enough that a hostile catalog cannot grow the registry.
    */
  val MaxErrorChars: Int = 2000

  /** Credential values shorter than this are not scrubbed: a two-character secret is not
    * recoverable from the error anyway, and blind-replacing a short string would shred unrelated
    * text (a value of "1" would redact every digit in the message).
    */
  private val MinCredentialChars = 4

  /** The credential-bearing options [[IcebergSetupSql.secretBlock]] emits. `CLIENT_ID` is NOT here;
    * see the class scaladoc.
    */
  private val CredentialLiteral =
    """(?i)\b(?:CLIENT_SECRET|TOKEN)\s+'((?:[^']|'')*)'""".r

  /** Base64 runs in the standard alphabet, long enough that a decode is worth attempting. That is
    * the alphabet HTTP Basic uses, which is the proven vector.
    */
  private val Base64Run = """[A-Za-z0-9+/]{12,}={0,2}""".r

  /** The plaintext credential values inside one rendered federation block.
    *
    * The block is authored by [[IcebergSetupSql.render]] and its secrets substituted by
    * `FederationBlobBuilder`, so the `NAME 'value'` shape and the doubled-quote escaping are both
    * ours: this parses SQL we generated, never operator-written SQL.
    */
  def credentialsIn(renderedSql: String): Set[String] =
    CredentialLiteral
      .findAllMatchIn(renderedSql)
      .map(_.group(1).replace("''", "'"))
      .filter(_.trim.nonEmpty)
      .toSet

  /** Redact `credentials` (and anything base64-encoding them) out of `error`, then cap its length.
    */
  def scrub(error: String, credentials: Set[String]): String =
    val creds   = credentials.map(_.trim).filter(_.length >= MinCredentialChars)
    val noRaw   = creds.foldLeft(error)((acc, c) => acc.replace(c, Marker))
    val noCoded =
      if creds.isEmpty then noRaw
      else
        Base64Run.replaceAllIn(
          noRaw,
          m =>
            if decodesToCredential(m.matched, creds) then Marker
            else java.util.regex.Matcher.quoteReplacement(m.matched)
        )
    if noCoded.length <= MaxErrorChars then noCoded
    else noCoded.take(MaxErrorChars) + " ... [truncated]"

  /** True when `run` base64-decodes to text containing any credential. A run that is not valid
    * base64, or decodes to bytes that are not text, simply is not a match.
    */
  private def decodesToCredential(run: String, creds: Set[String]): Boolean =
    try
      val decoded = new String(Base64.getMimeDecoder.decode(run), StandardCharsets.UTF_8)
      creds.exists(decoded.contains)
    catch case _: IllegalArgumentException => false
