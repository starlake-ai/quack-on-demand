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
      resolver = new PostgresSecretResolver()
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
  // scrub: the seven evasions the re-review demonstrated against the byte-exact version
  //
  // Every fixture below is chosen so the arm under test is the ONLY arm that can redact it. In
  // particular `plain` is lowercase with no base64 symbols and `opaque` is a twelve-character
  // lowercase word whose base64 happens to carry no digits and no padding, so the shape-based
  // blanket arm skips both: if these tests pass, they pass because the precise arm worked.
  // ---------------------------------------------------------------------------------------------

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
    val out = AttachErrorRedactor.scrub(err, Set(plain))
    out should not include plain
    out should not include "lake%2Fpass%2F9"
    out should include("catalog rejected")
  }

  it should "evasion 2: remove a credential the catalog JSON-escaped" in {
    // Also no adversary needed: several JSON encoders escape '/' as '\/' by default.
    val err = "Invalid Configuration Error: {\"rejected\": \"lake\\/pass\\/9\"} was refused"
    val out = AttachErrorRedactor.scrub(err, Set(plain))
    out should not include plain
    out should not include "lake\\/pass\\/9"
    out should include("was refused")
  }

  it should "evasion 3: remove a credential split across a newline" in {
    val err = s"Invalid Configuration Error: rejected lake/pass\n/9 today"
    val out = AttachErrorRedactor.scrub(err, Set(plain))
    out should not include plain
    out.replaceAll("\\s", "") should not include plain.replaceAll("\\s", "")
    out should include("rejected")
  }

  it should "evasion 4: remove a base64 blob split across a newline" in {
    val split = opaqueB64.take(8) + "\n" + opaqueB64.drop(8)
    val err   = s"Invalid Configuration Error: rejected:$split here"
    val out   = AttachErrorRedactor.scrub(err, Set(opaque))
    out.replaceAll("\\s", "") should not include opaqueB64
    out should include("rejected")
  }

  it should "evasion 5: remove a base64 blob glued to the preceding word" in {
    // The glue is five characters, so the blob starts at a base64 group offset of 1: a decoder
    // that only ever tries offset 0 reads garbage and passes the secret through.
    val err = s"Invalid Configuration Error: auths$opaqueB64 was refused"
    val out = AttachErrorRedactor.scrub(err, Set(opaque))
    out should not include opaqueB64
    out should include("was refused")
  }

  it should "evasion 6: remove a hex-encoded credential" in {
    val err = s"Invalid Configuration Error: rejected $opaqueHex now"
    val out = AttachErrorRedactor.scrub(err, Set(opaque))
    out should not include opaqueHex
    out should include("rejected")
  }

  it should "evasion 7: remove a double-base64-encoded credential" in {
    val doubled = Base64.getEncoder.encodeToString(opaqueB64.getBytes(StandardCharsets.UTF_8))
    val err     = s"Invalid Configuration Error: rejected $doubled now"
    val out     = AttachErrorRedactor.scrub(err, Set(opaque))
    out should not include doubled
    out should include("rejected")
  }

  it should "mask an encoding it cannot decode at all, on shape alone" in {
    // base32 is not one of the encodings scrub decodes, and the credential set cannot help here.
    // The fail-safe arm has to catch it on shape, or an encoding nobody modelled walks straight
    // through. This is the arm that decides the object over-redacts rather than under-redacts.
    val err = s"Invalid Configuration Error: rejected $opaqueB32 now"
    val out = AttachErrorRedactor.scrub(err, Set(opaque))
    out should not include opaqueB32
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
