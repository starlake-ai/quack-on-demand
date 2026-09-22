package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.{
  FederationBlobBuilder,
  PostgresSecretResolver,
  ResolvedFederationBlock
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.Base64

class AttachErrorRedactorSpec extends AnyFlatSpec with Matchers:

  private val alias = "sales_lake"

  // ---------------------------------------------------------------------------------------------
  // Fixtures rendered by the REAL generator, never hand-copied.
  //
  // The previous version of this spec typed out an imitation of `IcebergSetupSql.render`'s output
  // and asserted against that, so a credential-bearing option added to the generator would have
  // escaped redaction with the suite green. Everything below goes through
  // `IcebergRestConfig.validated` -> `IcebergSetupSql.render` -> `FederationBlobBuilder.buildOne`,
  // i.e. the same two components production uses, in the same order.
  // ---------------------------------------------------------------------------------------------

  private def sourceOf(cfg: IcebergRestConfig) = FederatedSource(
    id = "fs-1",
    tenantDbId = "td-1",
    alias = alias,
    sourceType = FederatedSourceType.IcebergRest,
    config = Some(cfg.toJson)
  )

  private def renderReal(
      cfg: IcebergRestConfig,
      secrets: Map[String, String]
  ): ResolvedFederationBlock =
    val src  = sourceOf(cfg)
    val rows = secrets.toList.map((n, v) => FederatedSecret(s"sec-$n", src.id, n, Some(v), None))
    new FederationBlobBuilder(
      loadEnabled = _ => IO.pure(List(src)),
      loadSecrets = _ => IO.pure(rows),
      resolver = new PostgresSecretResolver(),
      // This spec is about redaction, not alias reservation: the one source has no sibling and
      // this fixture declares no tenant-db catalog alias, so nothing is reserved.
      catalogAliasOf = _ => IO.pure(None)
    ).buildOne(src).unsafeRunSync()

  private def secretName(field: String): String  = "S_" + field.toUpperCase
  private def secretValue(field: String): String = s"SENTINEL-VALUE-FOR-$field-AAA111"

  /** One field of `cfg` set to its `{{secret.NAME}}` placeholder, or None when the field's type
    * refuses a free-form string (the two enums) -- discovered through the codec, so a field added
    * to `IcebergRestConfig` needs no edit here.
    */
  private def withPlaceholder(cfg: IcebergRestConfig, field: String): Option[IcebergRestConfig] =
    val candidate = cfg.asJson.deepMerge(
      Json.obj(field -> Json.fromString(s"{{secret.${secretName(field)}}}"))
    )
    IcebergRestConfig.fromJson(candidate.noSpaces).toOption

  /** The LARGEST set of placeholder-bearing fields this shape's validator accepts, with the value
    * each placeholder resolves to.
    *
    * It searches the subsets rather than adding fields one at a time because the validator has
    * mutual requirements -- under oauth2, `clientId` alone is invalid and `clientSecret` alone is
    * invalid, while the two together are valid -- so a greedy pass would find nothing at all and
    * the spec would silently test an empty fixture. Eight string-typed fields is 256 subsets,
    * checked largest first and stopped at the first valid one.
    */
  private def withPlaceholders(
      base: IcebergRestConfig
  ): (IcebergRestConfig, Map[String, String]) =
    val settable = base.productElementNames.toList.filter(withPlaceholder(base, _).isDefined)
    val subsets  =
      settable.foldLeft(List(List.empty[String]))((acc, f) => acc ++ acc.map(_ :+ f))
    val chosen = subsets
      .sortBy(fields => (-fields.size, fields.mkString(",")))
      .view
      .flatMap { fields =>
        fields
          .foldLeft(Option(base))((cfg, f) => cfg.flatMap(withPlaceholder(_, f)))
          .filter(IcebergRestConfig.validated(_, alias).isRight)
          .map(cfg => (cfg, fields))
      }
      .headOption
    val (cfg, fields) = chosen.getOrElse(
      fail(s"no placeholder-bearing field set validates for this shape: $base")
    )
    (cfg, fields.map(f => f -> secretValue(f)).toMap)

  /** Every auth shape the config type can express, derived from the enums rather than listed, so a
    * new `IcebergAuthType` or `IcebergEndpointType` is covered the day it is added.
    */
  private val shapes: List[(String, IcebergRestConfig)] =
    IcebergAuthType.values.toList.map(t =>
      t.wire -> IcebergRestConfig(uri = "http://c", warehouse = "w", authType = Some(t))
    ) ++ IcebergEndpointType.values.toList.map(e =>
      e.wire -> IcebergRestConfig(uri = "http://c", warehouse = "w", endpointType = Some(e))
    )

  // ---------------------------------------------------------------------------------------------
  // credentialsIn, tied to the generator
  // ---------------------------------------------------------------------------------------------

  "credentialsIn" should "recover every credential the real generator emits, for every shape" in
    shapes.foreach { (name, base) =>
      val (cfg, sentinels) = withPlaceholders(base)
      val block            = renderReal(cfg, sentinels.map((f, v) => secretName(f) -> v))
      // The credential-bearing fields are declared once, on the config type, and `validate` reads
      // that same list. Adding a credential to the generator without teaching the redactor about
      // it fails right here.
      val expected = IcebergRestConfig.CredentialFields.flatMap((f, _) => sentinels.get(f)).toSet
      withClue(s"shape '$name' rendered as:\n${block.sql}\n") {
        AttachErrorRedactor.credentialsIn(block.sql) shouldBe expected
        // Every sentinel that was placed is one the builder actually substituted, and the builder
        // reports them all -- that set, not this parse, is the redactor's primary input.
        block.secretValues shouldBe sentinels.values.toSet
      }
    }

  it should "not treat the client id as a credential, even though the generator emits it" in {
    val (cfg, sentinels) = withPlaceholders(shapes.find(_._1 == "oauth2").get._2)
    val block            = renderReal(cfg, sentinels.map((f, v) => secretName(f) -> v))
    block.sql should include(s"CLIENT_ID '${sentinels("clientId")}'")
    AttachErrorRedactor.credentialsIn(block.sql) should not contain sentinels("clientId")
  }

  it should "un-double the escaped quotes of a credential that contains one" in {
    val cfg = IcebergRestConfig(
      uri = "http://c",
      warehouse = "w",
      authType = Some(IcebergAuthType.Token),
      token = Some("{{secret.TOK}}")
    )
    val block = renderReal(cfg, Map("TOK" -> "ab'cd1234"))
    block.sql should include("TOKEN 'ab''cd1234'")
    AttachErrorRedactor.credentialsIn(block.sql) shouldBe Set("ab'cd1234")
  }

  /** The one list-versus-list check left in the suite, kept because it FAILS LOUDLY instead of
    * silently under-covering: a field added to `IcebergRestConfig` must be classified (added to
    * `CredentialFields`, or consciously left out as non-secret) and, if the generator emits it as a
    * credential, given an `IcebergSetupSql.CredentialOption` case. Every other tie in this spec is
    * derived; this one exists so "someone added a field and classified nothing" is impossible to
    * miss.
    */
  it should "fail when the config or the generator grows a field, until it is classified" in {
    IcebergRestConfig().productElementNames.toSet shouldBe Set(
      "uri",
      "warehouse",
      "authType",
      "endpointType",
      "clientId",
      "clientSecret",
      "oauth2ServerUri",
      "oauth2Scope",
      "oauth2GrantType",
      "token"
    )
    IcebergRestConfig.CredentialFields.map(_._1) shouldBe List("clientSecret", "token")
    IcebergSetupSql.CredentialOption.values.map(_.optionName).toSet shouldBe
      Set("CLIENT_SECRET", "TOKEN")
  }

  // ---------------------------------------------------------------------------------------------
  // scrub: the proven vector
  // ---------------------------------------------------------------------------------------------

  private val sentinel = "SENTINEL_CLIENT_SECRET_AAA111"

  "scrub" should "remove a credential echoed back in plaintext" in {
    val err = s"Invalid Configuration Error: rejected credential $sentinel"
    AttachErrorRedactor.scrub(err, Set(sentinel)) shouldBe
      "Invalid Configuration Error: rejected credential [redacted]"
  }

  // The proven vector: DuckDB sends oauth2 credentials as `Authorization: Basic
  // base64(client_id:client_secret)` and splices the catalog's response body verbatim into its
  // error, so a catalog that echoes the header it received leaks the plaintext secret. The
  // credential set here is the one the REAL builder reports for the REAL rendered block.
  it should "remove a credential carried inside an HTTP Basic blob" in {
    val cfg = IcebergRestConfig(
      uri = "http://127.0.0.1:18098",
      warehouse = "probe_warehouse",
      authType = Some(IcebergAuthType.OAuth2),
      clientId = Some("qodprobeclient"),
      clientSecret = Some("{{secret.CSEC}}")
    )
    val block = renderReal(cfg, Map("CSEC" -> sentinel))
    val basic = Base64.getEncoder.encodeToString(
      s"qodprobeclient:$sentinel".getBytes(StandardCharsets.UTF_8)
    )
    val err =
      s"""Invalid Configuration Error: Could not get token from http://c/v1/oauth/tokens: """ +
        s"""HTTP Unauthorized_401 - {"error": {"message": "rejected credential Basic $basic"}}"""
    val out = AttachErrorRedactor.scrub(err, block.secretValues)
    out should not include basic
    out should not include sentinel
    new String(Base64.getMimeDecoder.decode(basic), StandardCharsets.UTF_8) should include(sentinel)
    out should include("Could not get token from http://c/v1/oauth/tokens")
    out should include("Unauthorized_401")
  }

  // ---------------------------------------------------------------------------------------------
  // scrub: the evasions the re-review and the task-8b review demonstrated
  //
  // Arm attribution is ASSERTED, not claimed in a comment. The previous version of this block
  // stated that every fixture was precise-arm-only; a differential run disproved that for two of
  // them, which is the failure mode a blanket fallback creates -- a test can name one arm and be
  // carried by another, and still be green. `precisely` below proves the blanket arm is blind to
  // the fixture before the real call runs, so a test that uses it can only pass on a
  // credential-aware arm. Tests that are ABOUT the blanket arm call `scrub` with an empty
  // credential set instead, which is the other half of the same differential.
  // ---------------------------------------------------------------------------------------------

  /** Scrub `err`, having first proven the blanket arm cannot see it.
    *
    * The empty-credential-set run is the discriminator: if the text comes back byte-identical when
    * the redactor knows no credentials at all, then whatever the real call redacts, only an arm
    * that consults the credential set can have redacted it.
    */
  private def precisely(err: String, creds: String*): String =
    withClue(s"the blanket arm must be blind to this fixture, so the assertion names one arm:") {
      AttachErrorRedactor.scrub(err, Set.empty) shouldBe err
    }
    AttachErrorRedactor.scrub(err, creds.toSet)

  private val plain = "lake/pass/9"

  private val opaque = "jaenrltskewq"

  /** base64(opaque): 16 characters, no digit and no padding, so the blanket arm skips it. */
  private val opaqueB64 = "amFlbnJsdHNrZXdx"

  /** hex(opaque): 24 lowercase hex characters, below the blanket arm's all-hex length floor. */
  private val opaqueHex = "6a61656e726c74736b657771"

  /** base32(opaque): an encoding `scrub` deliberately does not decode. */
  private val opaqueB32 = "NJQWK3TSNR2HG23FO5YQ"

  it should "evasion 1: remove a credential the catalog percent-encoded" in {
    // No adversary needed: an oauth2 token request body is application/x-www-form-urlencoded, so a
    // catalog echoing the body it received percent-encodes any '/', '+' or '=' in the secret.
    val err = s"Invalid Configuration Error: catalog rejected 'lake%2Fpass%2F9' as the credential"
    val out = precisely(err, plain)
    out should not include plain
    out should not include "lake%2Fpass%2F9"
    out should include("catalog rejected")
  }

  it should "evasion 2: remove a credential the catalog JSON-escaped" in {
    // Also no adversary needed: several JSON encoders escape '/' as '\/' by default.
    val err = "Invalid Configuration Error: {\"rejected\": \"lake\\/pass\\/9\"} was refused"
    val out = precisely(err, plain)
    out should not include plain
    out should not include "lake\\/pass\\/9"
    out should include("was refused")
  }

  it should "evasion 3: remove a credential split across a newline" in {
    val err = s"Invalid Configuration Error: rejected lake/pass\n/9 today"
    val out = precisely(err, plain)
    out should not include plain
    out.replaceAll("\\s", "") should not include plain.replaceAll("\\s", "")
    out should include("rejected")
  }

  it should "evasion 4: remove a base64 blob split across a newline" in {
    val split = opaqueB64.take(8) + "\n" + opaqueB64.drop(8)
    val err   = s"Invalid Configuration Error: rejected:$split here"
    val out   = precisely(err, opaque)
    out.replaceAll("\\s", "") should not include opaqueB64
    out should include("rejected")
  }

  it should "evasion 5: remove a base64 blob glued to the preceding word" in {
    // The glue is five characters, so the blob starts at a base64 group offset of 1: a decoder
    // that only ever tries offset 0 reads garbage and passes the secret through.
    val err = s"Invalid Configuration Error: auths$opaqueB64 was refused"
    val out = precisely(err, opaque)
    out should not include opaqueB64
    out should include("was refused")
  }

  it should "evasion 6: remove a hex-encoded credential" in {
    val err = s"Invalid Configuration Error: rejected $opaqueHex now"
    val out = precisely(err, opaque)
    out should not include opaqueHex
    out should include("rejected")
  }

  // The previous evasion-7 fixture was base64(base64(<12-char word>)): 24 characters with padding,
  // which the BLANKET arm redacts on shape, so it proved nothing about the depth-2 decode it was
  // named for and the suite contained no test of that arm at all. `short` is nine characters, so
  // its double encoding is 16 characters with no padding -- under the blanket floor, invisible to
  // the shape rule, and reachable ONLY by decoding twice. Mutating MaxDecodeDepth to 1 fails here.
  private val short = "jaenrltsk"

  /** base64(base64(short)): 16 characters, no padding, invisible to the blanket arm. */
  private val shortDoubleB64 = "YW1GbGJuSnNkSE5y"

  it should "evasion 7: remove a double-base64-encoded credential" in {
    Base64.getEncoder.encodeToString(
      Base64.getEncoder
        .encodeToString(short.getBytes(StandardCharsets.UTF_8))
        .getBytes(
          StandardCharsets.UTF_8
        )
    ) shouldBe shortDoubleB64
    val out = precisely(s"Invalid Configuration Error: rejected \"$shortDoubleB64\" now", short)
    out should not include shortDoubleB64
    out should include("rejected")
  }

  it should "evasion 7b: remove a double-base64 blob glued to the words around it" in {
    // The quoted form above is isolated by a character normalization keeps. Unquoted, whitespace
    // stripping glues the blob to `rejected` and `now`, the glued run decodes to garbage bytes
    // followed by the inner encoding, and decoding THAT from character zero reads the garbage and
    // gives up. Following the runs inside a decoding is what closes it; before that this exact
    // input came back byte-identical with the credential recoverable by two `base64 -d` calls.
    val out = precisely(s"Invalid Configuration Error: rejected $shortDoubleB64 now", short)
    out should not include shortDoubleB64
    // And the fix must not pay for it with the sentence: the whole-run fallback would have eaten
    // both neighbouring words.
    out should include("rejected")
    out should include("now")
  }

  it should "evasion 7c: remove a hex-of-base64 credential glued to the words around it" in {
    // The outer layer here is HEX, so the precise base64-inside-base64 mapping cannot reach it and
    // the whole-run fallback has to. That fallback decodes the run and then looks for encoded runs
    // INSIDE the decoding rather than decoding the decoding from character zero: gluing puts a
    // stray byte in front of the inner blob (the trailing `ed` of "rejected" is itself hex), and
    // decoding from character zero reads that byte, finds no alphabet characters and gives up.
    val doubled = Base64.getEncoder
      .encodeToString(short.getBytes(StandardCharsets.UTF_8))
      .getBytes(StandardCharsets.UTF_8)
      .map("%02x".format(_))
      .mkString
    val out = precisely(s"Invalid Configuration Error: rejected $doubled now", short)
    out should not include doubled
    // The fallback takes the whole glued run, words included; that cost is why the precise
    // mappings exist for the shapes that can carry one.
    out should include("Invalid Configuration Error")
  }

  it should "evasion 7d: remove a hex-of-base64 credential behind a long garbage prefix" in {
    // The task-8b verification pass's exact input, and the fixture four earlier attempts missed.
    // `deadbeef` is eight hex characters, so the run decodes to FOUR garbage bytes in front of the
    // inner blob: one more than the four base64 alignments in `decodings` can skip, so decoding
    // the decoding from character zero reads garbage and gives up. 7c above sits on the other side
    // of that boundary (its prefix is one byte), which is why the run scan inside each decoding
    // looked unpinnable and was deleted. Only that scan reaches this one.
    //
    // The run is 24 lowercase hex characters, below the blanket arm's all-hex floor of 32, so no
    // shape rule can carry it -- `precisely` asserts that rather than assuming it.
    val tiny    = "abcd"
    val doubled = Base64.getEncoder
      .encodeToString(tiny.getBytes(StandardCharsets.UTF_8))
      .getBytes(StandardCharsets.UTF_8)
      .map("%02x".format(_))
      .mkString
    val blob = s"deadbeef$doubled"
    blob shouldBe "deadbeef59574a6a5a413d3d"
    val out = precisely(s"Invalid Configuration Error: rejected \"$blob\" now", tiny)
    out should not include blob
    // The quotes bound the run, so the fallback's whole-run cost stops at the blob here.
    out should include("rejected")
    out should include("now")
  }

  it should "mask an encoding it cannot decode at all, on shape alone" in {
    // The fail-safe arm on its own, with NO credential known: an encoding nobody modelled has to
    // be caught on shape or it walks straight through. This is the arm that decides the object
    // over-redacts rather than under-redacts. (`opaqueB32` is also decodable now, so the empty
    // credential set is what makes this a test of the blanket arm rather than of base32 support.)
    val err = s"Invalid Configuration Error: rejected $opaqueB32 now"
    val out = AttachErrorRedactor.scrub(err, Set.empty)
    out should not include opaqueB32
    out should include("rejected")
  }

  // ---------------------------------------------------------------------------------------------
  // scrub: the leaks the task-8b review drove against the compiled object
  // ---------------------------------------------------------------------------------------------

  /** Nine characters, so its base64 is twelve and its hex eighteen: both far below the blanket
    * arm's floor, and the shorter lengths below are shorter still. Credential LENGTH is chosen by
    * the operator, not by the catalog, so "our secrets are long" is not a property this object may
    * assume.
    */
  private val shortCred = "pw9-abcde"

  it should "remove base64 of a SHORT credential isolated by JSON quotes" in {
    // The reviewer's exact input. A JSON-quoted value is bounded on both sides by a character
    // normalization keeps, so the blob stands alone at its true length instead of being glued to
    // the surrounding words -- which is why every space-separated probe of this came back clean
    // while `base64 -d` recovered the secret from the stored text.
    val err =
      s"""Invalid Configuration Error: HTTP Unauthorized_401 - {"credential":"cHc5LWFiY2Rl"}"""
    Base64.getEncoder.encodeToString(shortCred.getBytes(StandardCharsets.UTF_8)) shouldBe
      "cHc5LWFiY2Rl"
    val out = precisely(err, shortCred)
    out should not include "cHc5LWFiY2Rl"
    out should include("Unauthorized_401")
  }

  it should "remove base64 and hex of a credential at every length it will scrub at all" in
    // A sweep rather than one fixture: the floor that leaked was a single constant, and a single
    // fixture only pins the length it happens to use. `MinCredentialChars` is 4, so from four
    // characters up the object claims coverage and this asserts it, in both alphabets, with the
    // blob isolated the way a JSON body isolates it.
    (4 to 12).foreach { n =>
      val secret = "pw9-abcdefgh".take(n)
      val b64    = Base64.getEncoder.encodeToString(secret.getBytes(StandardCharsets.UTF_8))
      val hex    = secret.getBytes(StandardCharsets.UTF_8).map("%02x".format(_)).mkString
      withClue(s"length $n, base64 '$b64': ") {
        precisely(s"""HTTP Unauthorized_401 - {"credential":"$b64"}""", secret) should
          not include b64
      }
      withClue(s"length $n, hex '$hex': ") {
        precisely(
          s"""HTTP Unauthorized_401 - {"credential":"$hex"}""",
          secret
        ) should not include hex
      }
    }

  it should "remove a credential chunked across separators the catalog inserted" in {
    // Declared unclosable by the previous version. It is not: a projection that drops every
    // non-alphanumeric character sees through it, fires only where the COMPLETE credential
    // appears, and feeds the existing span bookkeeping unchanged.
    val dotted = precisely(s"rejected ${sentinel.replace('_', '.')} now", sentinel)
    dotted should not include "SECRET"
    dotted should include("rejected")
    val comma = precisely("rejected SENTIN,EL_CLI,ENT_SE,CRET_A,AA111 now", sentinel)
    comma should not include "CRET_A"
    comma should include("now")
  }

  it should "remove a credential the catalog echoed back case-folded" in {
    val out = precisely(s"rejected ${sentinel.toLowerCase} now", sentinel)
    out should not include sentinel.toLowerCase
    out should include("rejected")
  }

  it should "remove a base64 blob split by a u-escaped whitespace character" in {
    // The short escapes `\n` `\r` `\t` were dropped while the `\uXXXX` branch MATERIALIZED its
    // character, so the same JSON escape behaved two different ways and the scaladoc claimed
    // otherwise. The fixture is a base64 BLOB rather than a plaintext credential on purpose: a
    // split plaintext value is also caught by the punctuation-dropping projection, which would
    // carry the test and prove nothing about this line. The decode arms never see that projection,
    // so a materialized newline inside a blob really does split the run in two and neither half
    // decodes.
    val split = opaqueB64.take(8) + "\\u000A" + opaqueB64.drop(8)
    val out   = precisely(s"Invalid Configuration Error: rejected:$split here", opaque)
    out should not include opaqueB64.drop(8)
    out should include("here")
  }

  it should "remove a credential that went through form encoding twice" in {
    val out = precisely("rejected 'lake%252Fpass%252F9' now", plain)
    out should not include "lake%252Fpass"
    out should include("rejected")
  }

  it should "remove a credential whose spaces a form encoder wrote as plus signs" in {
    val out = precisely("rejected 'lake+pass+9' now", "lake pass 9")
    out should not include "lake+pass+9"
    out should include("rejected")
  }

  it should "decode base32 of a credential too short for the blanket arm to see" in {
    // base32 of twelve bytes or fewer is 16 to 19 characters, under the blanket floor. Lowering
    // that floor would start eating `Unauthorized_401`, so the decode arm is where this closes.
    val out = precisely("rejected OB3S2YLCMNSGKZTH now", "pw-abcdefg")
    out should not include "OB3S2YLCMNSGKZTH"
    out should include("rejected")
  }

  it should "mask an encoded blob even when no credential is known at all" in {
    // The render-failure arm of IcebergAttachVerifier.note passes an empty credential set. With
    // the old credential-only rules that text went through untouched.
    val blob =
      Base64.getEncoder.encodeToString("client:s3cr3t-value".getBytes(StandardCharsets.UTF_8))
    AttachErrorRedactor.scrub(s"could not render attach SQL: $blob", Set.empty) should
      not include blob
  }

  // ---------------------------------------------------------------------------------------------
  // scrub: what must SURVIVE, so the object stays a diagnostic and not a black box
  // ---------------------------------------------------------------------------------------------

  it should "leave an error that carries no credential untouched" in {
    val err = "Invalid Configuration Error: No ICEBERG secret by the name of 'qod_ice_sales' " +
      "could be found"
    AttachErrorRedactor.scrub(err, Set(sentinel)) shouldBe err
  }

  it should "leave an unrelated base64-looking run alone" in {
    val err = "Binder Error: unknown parameter in AAAABBBBCCCCDDDDEEEE"
    AttachErrorRedactor.scrub(err, Set(sentinel)) shouldBe err
  }

  it should "leave DuckDB's own framing, including the catalog URL and HTTP status, intact" in {
    val err = "Invalid Configuration Error: Could not get token from " +
      "https://catalog.internal.example.net/iceberg/v1/oauth/tokens: HTTP Unauthorized_401"
    AttachErrorRedactor.scrub(err, Set(sentinel)) shouldBe err
  }

  it should "leave the Iceberg REST namespace separator, and the whole URL around it, intact" in {
    // The case above is all-lowercase with no percent escape, i.e. the one URL shape the blanket
    // arm happened not to match. It pinned the surviving shape rather than the class. The Iceberg
    // REST specification REQUIRES percent-encoding the multi-level namespace separator as %1F, so
    // a percent escape in a catalog path is normal traffic; when the blanket run spanned the slash
    // this whole URL collapsed to `https://catalog.example.[redacted]`.
    val err = "HTTP Error: Unable to connect to URL " +
      "\"https://catalog.example.com/v1/namespaces/sales%1Forders/tables\": 404 (Not Found)"
    AttachErrorRedactor.scrub(err, Set(sentinel)) shouldBe err
  }

  it should "keep the host and the path structure of a URL whose segment it does mask" in {
    // An uppercase-plus-digit path segment IS value-shaped, so the blanket arm masking it is the
    // intended trade. What must not happen is losing the rest: an operator who cannot see which
    // catalog, which API version and which position failed has lost the diagnostic this feature
    // exists to provide.
    val err = "IO Error: Connection error for HTTP GET to " +
      "'https://catalog.example.com/v1/namespaces/AnalyticsWarehouse2024/tables'"
    val out = AttachErrorRedactor.scrub(err, Set(sentinel))
    out should not include "AnalyticsWarehouse2024"
    out shouldBe "IO Error: Connection error for HTTP GET to " +
      "'https://catalog.example.com/v1/namespaces/[redacted]/tables'"
  }

  it should "not chase a credential whose punctuation-free form is too short to be one" in {
    // The glued projection is the aggressive one: it sees through separator chunking, so it also
    // sees through the separators of ORDINARY text. `ab-c` passes the credential floor at four
    // characters but projects to three, and blind-redacting every "abc" in the message would shred
    // the diagnostic to close a vector nobody can exploit at that length. The floor is applied to
    // the PROJECTED needle, not only to the credential.
    val err = "Invalid Configuration Error: the abc endpoint refused it"
    AttachErrorRedactor.scrub(err, Set("ab-c")) shouldBe err
  }

  it should "not shred the message for an implausibly short credential" in {
    val err = "Invalid Configuration Error: could not connect to a1"
    AttachErrorRedactor.scrub(err, Set("a1")) shouldBe err
  }

  // ---------------------------------------------------------------------------------------------
  // scrub: bounds
  // ---------------------------------------------------------------------------------------------

  it should "cap a hostile catalog's unbounded response body" in {
    val err = "Invalid Configuration Error: " + ("x" * 50000)
    val out = AttachErrorRedactor.scrub(err, Set.empty)
    out.length shouldBe AttachErrorRedactor.MaxErrorChars + " ... [truncated]".length
    out should endWith(" ... [truncated]")
  }

  it should "still redact inside the scanned window of a hostile body" in {
    // The pre-cap bounds the WORK, and it must not bound the redaction of anything that survives
    // into the stored string: the credential here sits well inside both caps.
    val err = "Invalid Configuration Error: " + sentinel + (" filler" * 50000)
    AttachErrorRedactor.scrub(err, Set(sentinel)) should not include sentinel
  }

  it should "redact a credential straddling the storage cap rather than truncating it in half" in {
    // Ordering proof, built so it can actually tell the two orders apart. The credential starts
    // four characters before the cap. Capping BEFORE the rules leaves "lake" in the output -- a
    // fragment the rules can no longer recognise, because the rest of the value was cut off.
    // Capping AFTER them, which is what the code does, replaces the whole value first and then
    // truncates the marker. (`plain` is deliberately the blanket-invisible fixture: with a
    // mixed-case value the shape arm would redact the fragment too and this would prove nothing.)
    val err = ("y" * (AttachErrorRedactor.MaxErrorChars - 4)) + plain + " tail"
    val out = AttachErrorRedactor.scrub(err, Set(plain))
    out should not include plain
    out should not include "lake"
    out should endWith(" ... [truncated]")
  }

  it should "produce one marker, not nested ones, when two rules cover the same blob" in {
    val err = s"rejected $sentinel"
    AttachErrorRedactor.scrub(err, Set(sentinel)) shouldBe "rejected [redacted]"
  }
