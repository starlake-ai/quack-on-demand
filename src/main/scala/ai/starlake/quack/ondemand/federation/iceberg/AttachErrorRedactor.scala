package ai.starlake.quack.ondemand.federation.iceberg

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable
import scala.util.matching.Regex

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
  * ==What this guarantees, and what it does not==
  *
  * The first version of this object matched BYTE-EXACT occurrences of the credential plus
  * standard-alphabet base64 of them, and was evaded by five near-miss encodings of the same secret,
  * two of which (percent-encoding, JSON `\/` escaping) an ordinary catalog emits with no malice at
  * all: they are what `application/x-www-form-urlencoded` bodies and several JSON encoders do on
  * their own. Chasing one pattern per encoding is a losing game, so this version does not. It
  * applies three detectors and OVER-REDACTS where they disagree:
  *
  *   1. `normalizedView` -- one shadow copy of the error with whitespace removed, percent-escapes
  *      decoded and backslash/JSON escapes undone, each shadow character carrying the span of the
  *      ORIGINAL text it came from. Every known credential value is searched for in that copy, and
  *      a hit redacts the original span. One projection closes percent-encoding, JSON escaping and
  *      any whitespace (including a JSON-escaped `\n`) inserted inside the value.
  *   2. a decode search over that same shadow copy: every run in the base64 / base64url / hex
  *      alphabet is decoded (at all four base64 group alignments, so a run glued to a preceding
  *      word still decodes; up to [[MaxDecodeDepth]] times, so double-encoding does not help) and
  *      the whole run is redacted if any decoding contains a credential. Running on the shadow copy
  *      is what makes a base64 blob wrapped at 76 columns, the MIME wire form, one contiguous run.
  *   3. a blanket mask, independent of the credential set: any run of [[MinOpaqueChars]]+
  *      characters in the encoded alphabets that LOOKS like encoded material (see `looksEncoded`)
  *      is redacted whether or not it decodes to anything we know. This is the fail-safe arm: it
  *      covers encodings this object does not model at all (base32, a catalog's own alphabet,
  *      compressed-then-encoded), at the cost of also eating long mixed-case identifiers.
  *
  * What it deliberately does NOT claim: a catalog that CHOOSES to leak can still chunk a secret
  * across separators this object treats as word boundaries (`a1b2.c3d4.e5f6`), and no rule that
  * keeps remote text readable can stop that -- only storing no remote text at all could, which
  * would also destroy the catalog's own message, the operator's primary answer to "why did this
  * catalog fail to attach". So the honest statement of the guarantee is: every credential this
  * manager handed the catalog is removed in plaintext and in every encoding modelled above, and
  * encoded-looking material is removed wholesale; a determined catalog operator -- who already
  * holds the secret they would be smuggling -- is not fully defeated. The realistic threat being
  * closed is credential SPRAWL: an ordinary catalog echoing the credential back into an API
  * response, a log line and a support bundle.
  *
  * The client id is deliberately NOT treated as a credential: on its own it is not a secret, and
  * keeping it visible is what makes "which principal was rejected" answerable. Detector 2 still
  * redacts the Basic blob that carries it, because that blob also carries the secret.
  *
  * The credential set itself no longer has to be re-derived by parsing: `FederationBlobBuilder`
  * hands [[IcebergAttachVerifier]] the values it substituted
  * ([[ai.starlake.quack.ondemand.federation.ResolvedFederationBlock]]), so the redactor learns the
  * secrets from the component that resolved them. [[credentialsIn]] remains as defence in depth for
  * a value that reached the SQL without going through substitution, and its keyword list is derived
  * from [[IcebergSetupSql.CredentialOption]] rather than hand-copied beside it.
  *
  * Bounds: the remote-controlled body is unbounded, so the text is pre-capped at [[MaxScanChars]]
  * BEFORE any detector runs (that is the work bound) and the result is capped at [[MaxErrorChars]]
  * after they run (that is the storage bound, applied last so a credential sitting at the boundary
  * is redacted rather than truncated into a decodable fragment).
  */
object AttachErrorRedactor:

  val Marker: String = "[redacted]"

  /** Cap on the stored error text. Generous enough for DuckDB's own message plus a realistic
    * catalog error body; small enough that a hostile catalog cannot grow the registry.
    */
  val MaxErrorChars: Int = 2000

  /** Cap on the text the detectors run over, applied BEFORE they run. A real DuckDB attach error is
    * a few hundred characters; this is four times the storage cap and orders of magnitude past
    * anything diagnostic, so it only ever truncates a body that was already going to be truncated.
    * Without it a catalog could make the manager base64-decode tens of megabytes per health tick.
    */
  val MaxScanChars: Int = 8192

  /** Credential values shorter than this are not scrubbed: a two-character secret is not
    * recoverable from the error anyway, and blind-replacing a short string would shred unrelated
    * text (a value of "1" would redact every digit in the message).
    */
  private val MinCredentialChars = 4

  /** Shortest run worth handing to the decode search. Four base64 characters carry three bytes, so
    * 16 characters is 12 bytes: below [[MinCredentialChars]] would be pointless, and a shorter
    * floor only buys decode attempts on ordinary words.
    */
  private val MinBlobChars = 16

  /** Shortest run the blanket mask will eat. Deliberately above the length of the framing tokens
    * DuckDB itself emits (`Unauthorized_401` is 16) so the diagnostic survives.
    */
  private val MinOpaqueChars = 20

  /** Shortest encoded text worth decoding: 8 base64 characters carry 6 bytes, 8 hex characters 4,
    * which is [[MinCredentialChars]].
    */
  private val MinDecodableChars = 8

  /** How many times a run is decoded before giving up, so base64(base64(secret)) is caught. */
  private val MaxDecodeDepth = 2

  /** The credential-bearing options [[IcebergSetupSql.secretBlock]] emits, DERIVED from the
    * generator's own declaration rather than hand-copied next to it: a new credential option has to
    * be added to [[IcebergSetupSql.CredentialOption]] to be rendered at all, and adding it there
    * widens this pattern in the same commit. `CLIENT_ID` is not one; see the class scaladoc.
    */
  private val CredentialLiteral: Regex =
    ("(?i)\\b(?:" + IcebergSetupSql.CredentialOption.values.map(_.optionName).mkString("|") +
      ")\\s+'((?:[^']|'')*)'").r

  /** Runs handed to the decode search: standard base64, base64url and hex all live in here. */
  private val BlobRun: Regex = ("[A-Za-z0-9+/=_-]{" + MinBlobChars + ",}").r

  /** Runs handed to the blanket mask. Wider than [[BlobRun]] by `%`, so a percent-encoded blob is
    * one run rather than a dozen fragments.
    */
  private val OpaqueRun: Regex = ("[A-Za-z0-9+/=%_-]{" + MinOpaqueChars + ",}").r

  /** Maximal hex substrings inside a run, so a hex blob glued to a word is still decodable. */
  private val HexRun: Regex = "[0-9a-fA-F]{8,}".r

  /** The plaintext credential values inside one rendered federation block.
    *
    * Defence in depth only: the authoritative credential set is the one
    * `FederationBlobBuilder.buildOne` reports, because that is the component that actually resolved
    * the secrets. This parse still runs because it costs nothing and covers a value that reached
    * the SQL without passing through substitution.
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

  /** Redact `credentials` (and anything encoding them, and anything that merely looks encoded) out
    * of `error`, then cap its length. See the class scaladoc for the guarantee this carries.
    */
  def scrub(error: String, credentials: Set[String]): String =
    val bounded = if error.length <= MaxScanChars then error else error.take(MaxScanChars)
    val creds   = credentials.map(_.trim).filter(_.length >= MinCredentialChars)
    val view    = normalizedView(bounded)
    val spans   = literalSpans(view, creds) ++ decodedSpans(view, creds) ++ opaqueSpans(bounded)
    val safe    = splice(bounded, spans)
    if safe.length <= MaxErrorChars then safe else safe.take(MaxErrorChars) + " ... [truncated]"

  /** One character of the shadow copy, plus the half-open span of the ORIGINAL string it came from.
    * Keeping the spans is what lets a match in the shadow copy redact the right bytes of the text
    * that actually gets stored, instead of storing the normalized form (which would itself be a
    * decoded credential).
    */
  private final class View(val text: String, val starts: Array[Int], val ends: Array[Int])

  /** Whitespace removed, `%XX` decoded, backslash escapes undone. Every transform here is one an
    * ordinary HTTP or JSON stack applies on its own, which is why the first version of this object
    * leaked without anyone attacking it.
    */
  private def normalizedView(s: String): View =
    val sb     = new mutable.StringBuilder(s.length)
    val starts = mutable.ArrayBuffer.empty[Int]
    val ends   = mutable.ArrayBuffer.empty[Int]
    var i      = 0
    while i < s.length do
      val c = s.charAt(i)
      if c.isWhitespace then i += 1
      else if c == '%' && i + 2 < s.length && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2)) then
        sb += (((hexVal(s.charAt(i + 1)) << 4) | hexVal(s.charAt(i + 2))).toChar)
        starts += i
        ends += (i + 3)
        i += 3
      else if c == '\\' && i + 1 < s.length then
        val n = s.charAt(i + 1)
        if n == 'u' && i + 5 < s.length && (2 to 5).forall(k => isHex(s.charAt(i + k))) then
          sb += Integer.parseInt(s.substring(i + 2, i + 6), 16).toChar
          starts += i
          ends += (i + 6)
          i += 6
        else if n == 'n' || n == 'r' || n == 't' || n == 'b' || n == 'f' then
          // An escaped whitespace character, dropped for the same reason a literal one is: it is
          // the cheapest way to split a secret without changing it.
          i += 2
        else if n == '/' || n == '\\' || n == '"' || n == '\'' then
          sb += n
          starts += i
          ends += (i + 2)
          i += 2
        else
          sb += c
          starts += i
          ends += (i + 1)
          i += 1
      else
        sb += c
        starts += i
        ends += (i + 1)
        i += 1
    new View(sb.result(), starts.toArray, ends.toArray)

  /** Spans of the original text whose NORMALIZED projection is a known credential. Both the raw and
    * the normalized form of the credential are searched, so a credential that itself contains a
    * percent sign or whitespace is still found.
    */
  private def literalSpans(view: View, creds: Set[String]): List[(Int, Int)] =
    val needles = creds.flatMap(c => Set(c, normalizedView(c).text)).filter(_.nonEmpty)
    needles.toList.flatMap { needle =>
      val out = mutable.ListBuffer.empty[(Int, Int)]
      var at  = view.text.indexOf(needle)
      while at >= 0 do
        out += ((view.starts(at), view.ends(at + needle.length - 1)))
        at = view.text.indexOf(needle, at + 1)
      out.toList
    }

  /** Spans of the original text covered by a run that DECODES to a credential.
    *
    * The run is located in the whitespace-stripped shadow copy, so a blob wrapped across lines is
    * one run -- but that same stripping glues the blob to the words around it, so redacting the
    * WHOLE matched run would eat the rest of the sentence. [[credentialRanges]] therefore maps a
    * decoded hit back onto the exact characters that encode it, and only a hit that can be located
    * no more precisely than "somewhere in this run" (a doubly-encoded one) takes the whole run.
    */
  private def decodedSpans(view: View, creds: Set[String]): List[(Int, Int)] =
    if creds.isEmpty then Nil
    else
      BlobRun
        .findAllMatchIn(view.text)
        .flatMap { m =>
          credentialRanges(m.matched, creds).map { (from, to) =>
            (view.starts(m.start + from), view.ends(m.start + to - 1))
          }
        }
        .toList

  /** The fail-safe arm: spans that merely LOOK like encoded material, redacted without asking what
    * they decode to. Runs on the ORIGINAL text, never on the whitespace-stripped view, because
    * stripping whitespace would glue ordinary prose into one long mixed-case run and eat the entire
    * diagnostic.
    */
  private def opaqueSpans(s: String): List[(Int, Int)] =
    OpaqueRun
      .findAllMatchIn(s)
      .filter(m => looksEncoded(m.matched))
      .map(m => (m.start, m.end))
      .toList

  /** Replace every (possibly overlapping) span with [[Marker]], once. Overlaps are merged first so
    * two detectors agreeing on the same blob produce one marker, not nested ones.
    */
  private def splice(s: String, spans: List[(Int, Int)]): String =
    if spans.isEmpty then s
    else
      val sorted = spans.sortBy(_._1)
      val merged = sorted.tail
        .foldLeft(List(sorted.head)) { (acc, span) =>
          val (lastStart, lastEnd) = acc.head
          if span._1 <= lastEnd then (lastStart, math.max(lastEnd, span._2)) :: acc.tail
          else span :: acc
        }
        .reverse
      val sb     = new mutable.StringBuilder(s.length)
      var cursor = 0
      merged.foreach { case (from, to) =>
        if from > cursor then sb ++= s.substring(cursor, from)
        sb ++= Marker
        cursor = math.max(cursor, to)
      }
      sb ++= s.substring(cursor)
      sb.result()

  /** Where inside `run` each credential is encoded, in `run`'s own coordinates.
    *
    * base64 maps 4 characters onto 3 bytes, so byte `b` of the decoding is carried by characters
    * `[(b / 3) * 4, ceil((b + 1) / 3) * 4)` of the encoded text -- offset by the alignment the
    * decode started at. Trying all four alignments is what finds a blob glued to a preceding word
    * (base64 groups repeat every 4 characters, so decoding from `prefixLength mod 4` reproduces
    * every byte from the real start onwards). Hex is the same idea at 2 characters per byte, tried
    * at both parities and over each maximal hex substring, so hex glued to a word is found too.
    *
    * Falls back to the whole run when a credential is only reachable through more than one decode
    * (base64 of base64): the byte-to-character mapping does not survive that, and over-redacting
    * one run is the safe side to fail on.
    */
  private def credentialRanges(run: String, creds: Set[String]): List[(Int, Int)] =
    val fromBase64 = (0 to 3).toList.flatMap { offset =>
      decodeBase64(run.drop(offset)).toList.flatMap { bytes =>
        hits(byteText(bytes), creds).map { (at, length) =>
          (offset + (at / 3) * 4, offset + ((at + length + 2) / 3) * 4)
        }
      }
    }
    val fromHex = HexRun.findAllMatchIn(run).toList.flatMap { hm =>
      (0 to 1).toList.flatMap { offset =>
        val body = hm.matched.drop(offset)
        val even = body.take(body.length - body.length % 2)
        if even.length < MinDecodableChars then Nil
        else
          hits(byteText(decodeHex(even)), creds).map { (at, length) =>
            (hm.start + offset + 2 * at, hm.start + offset + 2 * (at + length))
          }
      }
    }
    val precise = (fromBase64 ++ fromHex)
      .map((from, to) => (math.max(0, from), math.min(run.length, to)))
      .filter((from, to) => from < to)
    if precise.nonEmpty then precise
    else if decodesToCredential(run, creds, MaxDecodeDepth) then List((0, run.length))
    else Nil

  /** True when `run`, decoded up to `depth` times, yields text containing any credential. Only used
    * for the whole-run fallback above; a run that is not valid base64 or hex, or that decodes to
    * bytes containing nothing we know, simply is not a match.
    */
  private def decodesToCredential(run: String, creds: Set[String], depth: Int): Boolean =
    if depth <= 0 then false
    else
      decodings(run).exists(text =>
        hits(text, creds).nonEmpty || decodesToCredential(text, creds, depth - 1)
      )

  private def decodings(run: String): List[String] =
    (0 to 3).toList.flatMap(offset => decodeBase64(run.drop(offset)).map(byteText)) ++
      HexRun.findAllMatchIn(run).toList.flatMap { hm =>
        (0 to 1).toList.flatMap { offset =>
          val body = hm.matched.drop(offset)
          val even = body.take(body.length - body.length % 2)
          if even.length < MinDecodableChars then None else Some(byteText(decodeHex(even)))
        }
      }

  /** Every `(index, length)` at which a credential occurs in `text`, both measured in BYTES. */
  private def hits(text: String, creds: Set[String]): List[(Int, Int)] =
    creds.toList.flatMap { credential =>
      val needle = byteText(credential.getBytes(StandardCharsets.UTF_8))
      val out    = mutable.ListBuffer.empty[(Int, Int)]
      var at     = text.indexOf(needle)
      while at >= 0 do
        out += ((at, needle.length))
        at = text.indexOf(needle, at + 1)
      out.toList
    }

  /** One char per byte, so an index into the result IS a byte index. Decoding as UTF-8 would fold
    * invalid sequences into replacement characters and shift every index after them, which would
    * make the mapping back onto the encoded run point at the wrong characters -- and a mis-mapped
    * span leaves the credential in place, so this has to be exact rather than readable.
    */
  private def byteText(bytes: Array[Byte]): String =
    new String(bytes, StandardCharsets.ISO_8859_1)

  /** Base64-decode `s`, keeping the 4-characters-to-3-bytes index contract. base64url is folded
    * onto the standard alphabet first. Trailing `=` padding is kept when it lines up (so the last
    * one or two bytes, which may be where the credential ends, are recovered) and the text is
    * truncated to whole groups otherwise.
    */
  private def decodeBase64(s: String): Option[Array[Byte]] =
    val translated = s.map {
      case '-' => '+'
      case '_' => '/'
      case c   => c
    }
    val core = translated.takeWhile(isBase64)
    val pad  = translated.drop(core.length).takeWhile(_ == '=')
    val body =
      if pad.nonEmpty && pad.length <= 2 && (core.length + pad.length) % 4 == 0 then core + pad
      else core.take(core.length - core.length % 4)
    if body.length < MinDecodableChars then None
    else
      try Some(Base64.getDecoder.decode(body))
      catch case _: IllegalArgumentException => None

  private def decodeHex(s: String): Array[Byte] =
    Array.tabulate(s.length / 2)(i =>
      (((hexVal(s.charAt(2 * i)) << 4) | hexVal(s.charAt(2 * i + 1))) & 0xff).toByte
    )

  /** The blanket rule's shape test, tuned so DuckDB's own framing survives and encoded blobs do
    * not. A base64 or base64url blob of a random secret carries mixed case plus digits and usually
    * `+` / `/` / `=`; a percent-encoded value carries `%`; a hex blob is all hex and long. An
    * English word, a URL path segment and a snake_case alias carry none of those combinations,
    * which is why `Could not get token from https://idp/v1/oauth/tokens` comes through intact. The
    * cost is real and accepted: a long CamelCase identifier carrying a digit is redacted too.
    */
  private def looksEncoded(run: String): Boolean =
    val hasUpper  = run.exists(c => c >= 'A' && c <= 'Z')
    val hasLower  = run.exists(c => c >= 'a' && c <= 'z')
    val hasDigit  = run.exists(c => c >= '0' && c <= '9')
    val hasSymbol = run.exists(c => c == '+' || c == '=' || c == '%')
    hasSymbol || (hasUpper && hasDigit) || (run.forall(isHex) && run.length >= 32) ||
    (hasUpper && hasLower && run.length >= 24)

  private def isBase64(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' ||
      c == '/'

  private def isHex(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  private def hexVal(c: Char): Int =
    if c <= '9' then c - '0' else (Character.toLowerCase(c) - 'a') + 10
