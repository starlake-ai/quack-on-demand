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
  * ==Two failure modes, not one==
  *
  * A leak publishes a credential. But over-redaction destroys the operator's ONLY answer to "why
  * did this catalog fail to attach", which is the whole reason the error is stored at all. Both are
  * real failures, so the rules below are split by what they know:
  *
  *   - the PRECISE arms know the actual credential values, so they carry no length floor beyond
  *     "could this possibly carry [[MinCredentialChars]] bytes". They fire only where a credential
  *     really is present, so making them more sensitive costs no diagnostic.
  *   - the BLANKET arm knows nothing, so it fires on shape alone and its floor ([[MinOpaqueChars]])
  *     is what keeps DuckDB's own framing readable. It is also deliberately stopped at `/`, so a
  *     catalog URL keeps its scheme, host, path separators and the segments that are not
  *     value-shaped: an operator who cannot see WHICH namespace or table failed has lost the
  *     diagnostic just as surely as if the whole string had been dropped.
  *
  * ==What this guarantees, and what it does not==
  *
  * The first version of this object matched BYTE-EXACT occurrences of the credential plus
  * standard-alphabet base64 of them, and was evaded by five near-miss encodings of the same secret,
  * two of which (percent-encoding, JSON `\/` escaping) an ordinary catalog emits with no malice at
  * all: they are what `application/x-www-form-urlencoded` bodies and several JSON encoders do on
  * their own. Chasing one pattern per encoding is a losing game, so this version does not. It
  * applies four detectors and OVER-REDACTS where they disagree:
  *
  *   1. `normalize` -- a shadow copy of the error with whitespace removed, percent-escapes decoded
  *      and backslash/JSON escapes undone, each shadow character carrying the span of the ORIGINAL
  *      text it came from. Every known credential value is searched for in that copy, and a hit
  *      redacts the original span. One projection closes percent-encoding, JSON escaping and any
  *      whitespace (including a JSON-escaped `\n` and a `\u`-escaped one) inserted inside the
  *      value. It is applied TWICE, so `%252F` (a value that went through form encoding twice)
  *      decodes as well.
  *   2. a second shadow copy that additionally drops every non-alphanumeric character and folds
  *      case, matched only against credentials whose own projection still carries
  *      [[MinCredentialChars]] characters. This is what closes separator chunking
  *      (`SENTINEL.CLIENT.SECRET.AAA111`), plus-as-space form encoding, and a credential echoed
  *      back upper- or lower-cased. It fires only where the COMPLETE credential appears, so its
  *      diagnostic cost is bounded by the credential itself.
  *   3. a decode search over the shadow copies: every run in the base64 / base64url / hex / base32
  *      alphabet is decoded (at all four base64 group alignments, so a run glued to a preceding
  *      word still decodes; up to [[MaxDecodeDepth]] times, and each decoding is itself scanned for
  *      further encoded runs, so an inner blob sitting behind more garbage than the alignment sweep
  *      can skip is reached too) and the run is redacted if any decoding contains a credential. A
  *      doubly-encoded blob under a base64 OUTER layer is additionally located PRECISELY, by
  *      mapping the inner run back through the outer grouping, so the message loses the blob and
  *      keeps the words around it; under a hex or base32 outer layer the whole run is taken
  *      instead. What this does not reach is a THIRD layer, which [[MaxDecodeDepth]] bounds.
  *      Running on the shadow copy is what makes a base64 blob wrapped at 76 columns, the MIME wire
  *      form, one contiguous run.
  *   4. a blanket mask, independent of the credential set: any run of [[MinOpaqueChars]]+
  *      characters in the encoded alphabets that LOOKS like encoded material (see `looksEncoded`)
  *      is redacted whether or not it decodes to anything we know. This is the fail-safe arm: it
  *      covers encodings this object does not model at all (a catalog's own alphabet,
  *      compressed-then-encoded), at the cost of also eating long mixed-case identifiers.
  *
  * What it deliberately does NOT claim: a catalog that CHOOSES to leak can still apply an ARBITRARY
  * transform to the secret before echoing it -- a Caesar shift, a reversal, a compression -- and no
  * rule that keeps remote text readable can model a transform nobody has seen. That residual is
  * narrower than it used to be: separator chunking and case folding, which the previous version
  * also declared unclosable, are closed by detector 2. So the honest statement of the guarantee is:
  * every credential this manager handed the catalog is removed in plaintext, in every encoding
  * modelled above, chunked across separators, case-folded, and encoded-looking material is removed
  * wholesale; a catalog operator who invents their own transform -- and who already holds the
  * secret they would be smuggling -- is not defeated. The realistic threat being closed is
  * credential SPRAWL: an ordinary catalog echoing the credential back into an API response, a log
  * line and a support bundle.
  *
  * The client id is deliberately NOT treated as a credential: on its own it is not a secret, and
  * keeping it visible is what makes "which principal was rejected" answerable. Detector 3 still
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

  /** Shortest base64 text worth decoding. Six characters carry four bytes once the trailing partial
    * group is padded back out, which is exactly [[MinCredentialChars]].
    *
    * This floor belongs to the PRECISE arm and is therefore the true arithmetic minimum rather than
    * a safety margin. The previous version reused the blanket arm's order of magnitude here (16
    * characters, 12 bytes), which meant base64 of any credential of four to nine characters -- the
    * exact vector this object exists for, at a shorter secret -- walked straight through whenever a
    * delimiter normalization keeps, such as a JSON quote, isolated the blob at its true length.
    * Lowering it cannot over-redact, because the decode arms redact only what actually decodes to a
    * credential; the cost is decode attempts on ordinary words, which the [[MaxScanChars]] pre-cap
    * bounds.
    */
  private val MinBase64Chars = 6

  /** Shortest hex text worth decoding: eight characters carry four bytes. */
  private val MinHexChars = 8

  /** Shortest base32 text worth decoding: eight characters carry five bytes. */
  private val MinBase32Chars = 8

  /** Shortest run the blanket mask will eat. Deliberately above the length of the framing tokens
    * DuckDB itself emits (`Unauthorized_401` is 16) so the diagnostic survives. This floor is what
    * makes the blanket arm blind to short encodings; closing those is the precise arms' job, which
    * is why base32 is decoded rather than masked at a lower length.
    */
  private val MinOpaqueChars = 20

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

  /** Runs handed to the decode search: standard base64, base64url, hex and base32 all live in here.
    */
  private val BlobRun: Regex = ("[A-Za-z0-9+/=_-]{" + MinBase64Chars + ",}").r

  /** Runs handed to the blanket mask.
    *
    * `%` is in the alphabet so a percent-encoded blob is one run rather than a dozen fragments. `/`
    * deliberately is NOT: a URL path is the single most common thing in a DuckDB attach error, and
    * spanning the slash turned `https://catalog/v1/namespaces/Sales2024/tables` into
    * `https://catalog.example.[redacted]`, taking the host, the API version and the object name
    * with it. Splitting at the slash keeps all of that and still masks the value-shaped segment.
    * What it gives up is at most a leading fragment of a blob that happens to contain a `/` early;
    * the precise arms are unaffected because [[BlobRun]] is a separate pattern that keeps `/`.
    */
  private val OpaqueRun: Regex = ("[A-Za-z0-9+=%_-]{" + MinOpaqueChars + ",}").r

  /** Maximal hex substrings inside a run, so a hex blob glued to a word is still decodable. */
  private val HexRun: Regex = ("[0-9a-fA-F]{" + MinHexChars + ",}").r

  private val Base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

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
    val once    = normalize(identityView(bounded))
    val twice   = normalize(once)
    val folded  = foldCase(alphanumericOnly(twice))
    val exact   = creds.flatMap(c => Set(c, normalize(identityView(c)).text)).filter(_.nonEmpty)
    // A credential whose alphanumeric projection is shorter than the floor is NOT chased through
    // the glued view: "pw9-" projects to "pw9", and redacting every "pw9" in the message would
    // shred the diagnostic to close a vector nobody can exploit at that length.
    val glued = creds
      .map(c => foldCase(alphanumericOnly(normalize(identityView(c)))).text)
      .filter(_.length >= MinCredentialChars)
    val spans =
      literalSpans(once, exact) ++ literalSpans(twice, exact) ++ literalSpans(folded, glued) ++
        decodedSpans(once, creds) ++ decodedSpans(twice, creds) ++ opaqueSpans(bounded)
    val safe = splice(bounded, spans)
    if safe.length <= MaxErrorChars then safe else safe.take(MaxErrorChars) + " ... [truncated]"

  /** One character of a shadow copy, plus the half-open span of the ORIGINAL string it came from.
    * Keeping the spans is what lets a match in a shadow copy redact the right bytes of the text
    * that actually gets stored, instead of storing the normalized form (which would itself be a
    * decoded credential).
    *
    * Every projection below takes a `View` and returns a `View`, mapping spans straight through, so
    * projections compose: `alphanumericOnly(normalize(normalize(identityView(s))))` still points at
    * the original characters.
    */
  private final class View(val text: String, val starts: Array[Int], val ends: Array[Int])

  private def identityView(s: String): View =
    new View(s, Array.tabulate(s.length)(i => i), Array.tabulate(s.length)(i => i + 1))

  /** Whitespace removed, `%XX` decoded, backslash escapes undone. Every transform here is one an
    * ordinary HTTP or JSON stack applies on its own, which is why the first version of this object
    * leaked without anyone attacking it. Applying it twice (see [[scrub]]) covers a value that went
    * through such a stack twice, which is what makes `%252F` a decoded slash rather than a literal
    * percent.
    */
  private def normalize(v: View): View =
    val s                                          = v.text
    val sb                                         = new mutable.StringBuilder(s.length)
    val starts                                     = mutable.ArrayBuffer.empty[Int]
    val ends                                       = mutable.ArrayBuffer.empty[Int]
    def emit(c: Char, from: Int, until: Int): Unit =
      sb += c
      starts += v.starts(from)
      ends += v.ends(until - 1)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c.isWhitespace then i += 1
      else if c == '%' && i + 2 < s.length && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2)) then
        emit(((hexVal(s.charAt(i + 1)) << 4) | hexVal(s.charAt(i + 2))).toChar, i, i + 3)
        i += 3
      else if c == '\\' && i + 1 < s.length then
        val n = s.charAt(i + 1)
        if n == 'u' && i + 5 < s.length && (2 to 5).forall(k => isHex(s.charAt(i + k))) then
          // A `\u`-escaped whitespace character is dropped for exactly the reason a literal one
          // and a `\n` are: it is the cheapest way to split a secret without changing it.
          // Materializing it here while dropping `\n` three lines below is what let a credential
          // split by an escaped newline (the four hex digits 000A) through untouched.
          val decoded = Integer.parseInt(s.substring(i + 2, i + 6), 16).toChar
          if !decoded.isWhitespace then emit(decoded, i, i + 6)
          i += 6
        else if n == 'n' || n == 'r' || n == 't' || n == 'b' || n == 'f' then i += 2
        else if n == '/' || n == '\\' || n == '"' || n == '\'' then
          emit(n, i, i + 2)
          i += 2
        else
          emit(c, i, i + 1)
          i += 1
      else
        emit(c, i, i + 1)
        i += 1
    new View(sb.result(), starts.toArray, ends.toArray)

  /** Every non-alphanumeric character dropped. This is the projection that sees through separator
    * chunking (`a1b2.c3d4.e5f6`), through a form encoder's plus-for-space, and through any other
    * punctuation a catalog sprinkles into the value. It is matched ONLY against complete
    * credentials (see [[scrub]]), never handed to a decoder: gluing the message together would make
    * every word one run and the decode arms would chase garbage.
    */
  private def alphanumericOnly(v: View): View =
    val sb     = new mutable.StringBuilder(v.text.length)
    val starts = mutable.ArrayBuffer.empty[Int]
    val ends   = mutable.ArrayBuffer.empty[Int]
    var i      = 0
    while i < v.text.length do
      val c = v.text.charAt(i)
      if Character.isLetterOrDigit(c) then
        sb += c
        starts += v.starts(i)
        ends += v.ends(i)
      i += 1
    new View(sb.result(), starts.toArray, ends.toArray)

  /** Case folded, one character at a time so the index contract survives: `String.toLowerCase` can
    * change a string's LENGTH for some locales and code points, which would slide every span after
    * the change onto the wrong characters.
    */
  private def foldCase(v: View): View =
    new View(v.text.map(c => Character.toLowerCase(c)), v.starts, v.ends)

  /** Spans of the original text whose projection into `view` contains one of `needles`. */
  private def literalSpans(view: View, needles: Set[String]): List[(Int, Int)] =
    needles.toList.filter(_.nonEmpty).flatMap { needle =>
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
    * no more precisely than "somewhere in this run" (a doubly-encoded or base32 one) takes the
    * whole run.
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
    * base32 is the same idea again at 8 characters per 5 bytes, tried at all eight alignments. And
    * a DOUBLY-encoded credential is located by the same arithmetic one level up: the inner run is
    * found inside the first decoding and mapped back through the outer base64 grouping, so
    * `rejected <base64(base64(secret))> now` loses the blob and keeps both words. Only the inner
    * step is imprecise, and it is bounded by the inner run.
    *
    * Falls back to the whole run when a credential is reachable no more precisely than that (a
    * doubly-encoded hex or base32 blob): over-redacting one run is the safe side to fail on. That
    * run is whitespace-glued to its neighbouring words, so the fallback costs those words -- which
    * is why the precise mappings above are worth their arithmetic.
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
        if even.length < MinHexChars then Nil
        else
          hits(byteText(decodeHex(even)), creds).map { (at, length) =>
            (hm.start + offset + 2 * at, hm.start + offset + 2 * (at + length))
          }
      }
    }
    val fromBase32 = (0 to 7).toList.flatMap { offset =>
      decodeBase32(run.drop(offset)).toList.flatMap { bytes =>
        hits(byteText(bytes), creds).map { (at, length) =>
          (offset + (at / 5) * 8, offset + ((at + length + 4) / 5) * 8)
        }
      }
    }
    val fromNested = (0 to 3).toList.flatMap { offset =>
      decodeBase64(run.drop(offset)).toList.flatMap { bytes =>
        BlobRun
          .findAllMatchIn(byteText(bytes))
          .filter(inner => decodesToCredential(inner.matched, creds, MaxDecodeDepth - 1))
          .map(inner => (offset + (inner.start / 3) * 4, offset + ((inner.end + 2) / 3) * 4))
          .toList
      }
    }
    val precise = (fromBase64 ++ fromHex ++ fromBase32 ++ fromNested)
      .map((from, to) => (math.max(0, from), math.min(run.length, to)))
      .filter((from, to) => from < to)
    if precise.nonEmpty then precise
    else if decodesToCredential(run, creds, MaxDecodeDepth) then List((0, run.length))
    else Nil

  /** True when `run`, decoded up to `depth` times, yields text containing any credential. Only used
    * for the whole-run fallback above; a run that is not valid base64, hex or base32, or that
    * decodes to bytes containing nothing we know, simply is not a match.
    *
    * Each decoding is attacked two ways: decoded again from character zero, AND scanned for the
    * encoded-looking runs inside it. Decoding from zero reaches an inner encoding preceded by at
    * most three garbage bytes, because [[decodings]] retries at four base64 alignments; the run
    * scan is what reaches one preceded by MORE. The scan is not redundant with
    * [[credentialRanges]]'s `fromNested`, which covers a base64 OUTER layer only: under a hex outer
    * layer with a four-byte garbage prefix (`hex(base64(secret))` behind eight hex characters)
    * nothing else here reaches the credential, and the run stays below the all-hex blanket floor.
    * The scan was deleted once for failing no test, which was true of the four fixtures tried and
    * false of the one now pinning it in `AttachErrorRedactorSpec`.
    *
    * It is skipped at `depth == 1`, where the recursion it feeds could only answer false, so it
    * costs one regex pass per decoding of the outermost run and nothing below that.
    */
  private def decodesToCredential(run: String, creds: Set[String], depth: Int): Boolean =
    if depth <= 0 then false
    else
      decodings(run).exists { text =>
        hits(text, creds).nonEmpty || decodesToCredential(text, creds, depth - 1) ||
        (depth > 1 && BlobRun
          .findAllMatchIn(text)
          .exists(inner => decodesToCredential(inner.matched, creds, depth - 1)))
      }

  private def decodings(run: String): List[String] =
    (0 to 3).toList.flatMap(offset => decodeBase64(run.drop(offset)).map(byteText)) ++
      HexRun.findAllMatchIn(run).toList.flatMap { hm =>
        (0 to 1).toList.flatMap { offset =>
          val body = hm.matched.drop(offset)
          val even = body.take(body.length - body.length % 2)
          if even.length < MinHexChars then None else Some(byteText(decodeHex(even)))
        }
      } ++ (0 to 7).toList.flatMap(offset => decodeBase32(run.drop(offset)).map(byteText))

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
    * one or two bytes, which may be where the credential ends, are recovered); a trailing partial
    * group with no padding at all -- the unpadded base64url wire form -- has its padding
    * synthesized rather than being truncated away, because for a short credential those last bytes
    * are most of the secret.
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
      else
        core.length % 4 match
          // A lone trailing character carries 6 bits, no whole byte: it can only be dropped.
          case 1 => core.dropRight(1)
          case 2 => core + "=="
          case 3 => core + "="
          case _ => core
    if body.length < MinBase64Chars then None
    else
      try Some(Base64.getDecoder.decode(body))
      catch case _: IllegalArgumentException => None

  /** Base32-decode `s` (RFC 4648 alphabet, padding optional), five bits at a time.
    *
    * No index mapping back onto the run: base32's 8-characters-to-5-bytes grouping makes the
    * mapping coarse enough that a precise span would usually be the whole run anyway, so a base32
    * hit takes the run. This exists because base32 of a credential of twelve bytes or fewer is 16
    * to 19 characters, BELOW [[MinOpaqueChars]], and lowering that floor to reach it would start
    * eating `Unauthorized_401` and the rest of DuckDB's own framing. Decoding is the arm that can
    * close a short encoding without costing the diagnostic.
    */
  private def decodeBase32(s: String): Option[Array[Byte]] =
    val core = s.map(c => Character.toUpperCase(c)).takeWhile(c => Base32Alphabet.indexOf(c) >= 0)
    if core.length < MinBase32Chars then None
    else
      val out    = mutable.ArrayBuffer.empty[Byte]
      var buffer = 0
      var bits   = 0
      core.foreach { c =>
        buffer = (buffer << 5) | Base32Alphabet.indexOf(c)
        bits += 5
        if bits >= 8 then
          out += (((buffer >> (bits - 8)) & 0xff).toByte)
          bits -= 8
      }
      Some(out.toArray)

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
    *
    * It is a SHAPE test and therefore length-bound: a short encoding (base32 of twelve bytes or
    * fewer, base64 of a short secret) is below [[MinOpaqueChars]] and invisible here by
    * construction. Those are the precise arms' job, which is why they decode four alphabets. The
    * same division of labour is why `=` is read as padding rather than as a separator below.
    */
  private def looksEncoded(run: String): Boolean =
    // `hasSymbol` reads `=` only where base64 puts it: at the END of the run, as padding. A
    // MID-run `=` is a key/value separator, and counting it as encoding evidence made this arm eat
    // `warehouse=probe_warehouse` out of a catalog URL whole -- exactly the field that says WHICH
    // catalog was asked for. The security cost of narrowing it is bounded: the shape it stops
    // covering is an all-lowercase run carrying a `=`, and a credential of that shape is caught by
    // the PRECISE arms, which see it through percent-decoding, case folding and separator
    // chunking. Those arms need the credential set, and every path that feeds `scrub`
    // CATALOG-controlled text passes one (`IcebergAttachVerifier.reattach`'s failure arm); the two
    // `note` call sites that pass none carry manager-authored text, not the catalog's.
    val hasUpper  = run.exists(c => c >= 'A' && c <= 'Z')
    val hasLower  = run.exists(c => c >= 'a' && c <= 'z')
    val hasDigit  = run.exists(c => c >= '0' && c <= '9')
    val hasSymbol = run.exists(c => c == '+' || c == '%') || run.endsWith("=")
    hasSymbol || (hasUpper && hasDigit) || (run.forall(isHex) && run.length >= 32) ||
    (hasUpper && hasLower && run.length >= 24)

  private def isBase64(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' ||
      c == '/'

  private def isHex(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  private def hexVal(c: Char): Int =
    if c <= '9' then c - '0' else (Character.toLowerCase(c) - 'a') + 10
