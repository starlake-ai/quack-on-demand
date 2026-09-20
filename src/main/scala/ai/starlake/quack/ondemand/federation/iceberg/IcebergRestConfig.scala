package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.Names
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder}

/** How QoD authenticates to an Iceberg REST catalog. Maps onto DuckDB's `AUTHORIZATION_TYPE` ATTACH
  * option, except for `Token`: a bearer is expressed as a `TOKEN` on the ICEBERG secret with no
  * `AUTHORIZATION_TYPE` at all (DuckDB's valid set is none / oauth2 / sigv4, and oauth2 is its
  * default, which a token-bearing secret satisfies without an exchange).
  */
enum IcebergAuthType(val wire: String):
  case NoAuth extends IcebergAuthType("none")
  case OAuth2 extends IcebergAuthType("oauth2")
  case Token  extends IcebergAuthType("token")
  case SigV4  extends IcebergAuthType("sigv4")

object IcebergAuthType:
  def fromWire(s: String): Option[IcebergAuthType] =
    values.find(_.wire.equalsIgnoreCase(s.trim))

  given Encoder[IcebergAuthType] = Encoder.encodeString.contramap(_.wire)
  given Decoder[IcebergAuthType] = Decoder.decodeString.emap { s =>
    fromWire(s).toRight(s"unknown authType '$s' (expected one of: none, oauth2, token, sigv4)")
  }

/** DuckDB's `ENDPOINT_TYPE` ATTACH option. Mutually exclusive with `AUTHORIZATION_TYPE`: these
  * catalogs select their own signing.
  */
enum IcebergEndpointType(val wire: String):
  case Glue     extends IcebergEndpointType("glue")
  case S3Tables extends IcebergEndpointType("s3_tables")

object IcebergEndpointType:
  def fromWire(s: String): Option[IcebergEndpointType] =
    values.find(_.wire.equalsIgnoreCase(s.trim))

  given Encoder[IcebergEndpointType] = Encoder.encodeString.contramap(_.wire)
  given Decoder[IcebergEndpointType] = Decoder.decodeString.emap { s =>
    fromWire(s).toRight(s"unknown endpointType '$s' (expected one of: glue, s3_tables)")
  }

/** Typed declaration of one external Iceberg REST catalog, persisted as JSON in
  * `qodstate_federated_source.config`.
  *
  * `clientSecret` and `token` are the credential-bearing fields: `validate` enforces they hold a
  * `{{secret.NAME}}` placeholder rather than a literal value, resolved at node spawn by
  * [[ai.starlake.quack.ondemand.federation.FederationBlobBuilder]] against
  * `qodstate_federated_secret`, exactly as operator-written federation SQL is. That is why this
  * type is safe to return over REST unredacted. `clientId` is not constrained the same way: a
  * client id is routinely not sensitive and operators set it inline.
  */
final case class IcebergRestConfig(
    uri: String = "",
    warehouse: String = "",
    authType: Option[IcebergAuthType] = None,
    endpointType: Option[IcebergEndpointType] = None,
    clientId: Option[String] = None,
    clientSecret: Option[String] = None,
    oauth2ServerUri: Option[String] = None,
    oauth2Scope: Option[String] = None,
    oauth2GrantType: Option[String] = None,
    token: Option[String] = None
):

  def toJson: String = this.asJson.noSpaces

  /** Empty list when the config is usable. Every rule here mirrors an error the DuckDB iceberg
    * extension raises at ATTACH time; validating in the manager matters because a bad source takes
    * down the whole node's init SQL, not just its own catalog.
    */
  def validate(alias: String, reservedAliases: Set[String]): List[String] =
    val errs = List.newBuilder[String]

    if !Names.isValid(alias) then
      errs += s"alias '$alias' must be a plain SQL identifier of 1..${Names.MaxLength} chars " +
        "(letters, digits, underscore; not starting with a digit)"
    if (reservedAliases ++ IcebergRestConfig.ReservedAliases).exists(_.equalsIgnoreCase(alias)) then
      errs += s"alias '$alias' is reserved"

    if warehouse.trim.isEmpty then errs += "warehouse is required"

    // uri, warehouse, clientId and the oauth2 free-form fields MAY all legitimately carry a
    // well-formed {{secret.NAME}} placeholder (FederationBlobBuilder substitutes it wherever it
    // appears in the rendered SQL, not only in clientSecret/token). What must be caught is a
    // STRAY or MALFORMED brace - one that would not resolve and so would fail the whole
    // tenant-db federation blob.
    errs ++= IcebergRestConfig.strayBraceErrors("uri", Some(uri))
    errs ++= IcebergRestConfig.strayBraceErrors("warehouse", Some(warehouse))
    errs ++= IcebergRestConfig.strayBraceErrors("clientId", clientId)
    errs ++= IcebergRestConfig.strayBraceErrors("oauth2ServerUri", oauth2ServerUri)
    errs ++= IcebergRestConfig.strayBraceErrors("oauth2Scope", oauth2Scope)
    errs ++= IcebergRestConfig.strayBraceErrors("oauth2GrantType", oauth2GrantType)

    if authType.isDefined == endpointType.isDefined then
      errs += "set exactly one of authType / endpointType (DuckDB refuses AUTHORIZATION_TYPE " +
        "combined with ENDPOINT_TYPE)"

    if authType.isDefined && uri.trim.isEmpty then errs += "uri is required when authType is set"

    val credentialFieldsSet =
      IcebergRestConfig.isSet(clientId) || IcebergRestConfig.isSet(clientSecret) ||
        IcebergRestConfig.isSet(oauth2ServerUri) || IcebergRestConfig.isSet(oauth2Scope) ||
        IcebergRestConfig.isSet(oauth2GrantType) || IcebergRestConfig.isSet(token)

    authType match
      case Some(IcebergAuthType.OAuth2) =>
        if !IcebergRestConfig.isSet(clientId) then
          errs += "clientId is required for authType 'oauth2'"
        if !IcebergRestConfig.isSet(clientSecret) then
          errs += "clientSecret is required for authType 'oauth2'"
        if IcebergRestConfig.isSet(token) then errs += "token is not accepted for authType 'oauth2'"
      case Some(IcebergAuthType.Token) =>
        if !IcebergRestConfig.isSet(token) then errs += "token is required for authType 'token'"
        if IcebergRestConfig.isSet(clientId) || IcebergRestConfig.isSet(clientSecret) then
          errs += "clientId / clientSecret are not accepted for authType 'token'"
        if IcebergRestConfig.isSet(oauth2ServerUri) || IcebergRestConfig.isSet(oauth2Scope) ||
          IcebergRestConfig.isSet(oauth2GrantType)
        then
          errs += "oauth2ServerUri / oauth2Scope / oauth2GrantType are not accepted for " +
            "authType 'token'"
      case Some(other) =>
        if credentialFieldsSet then
          errs += s"authType '${other.wire}' takes no clientId, clientSecret, oauth2 or token fields"
      case None =>
        if credentialFieldsSet then
          errs += "endpointType takes no clientId, clientSecret, oauth2 or token fields"

    errs ++= IcebergRestConfig.placeholderErrors("clientSecret", clientSecret)
    errs ++= IcebergRestConfig.placeholderErrors("token", token)

    errs.result()

/** An [[IcebergRestConfig]] that has passed [[IcebergRestConfig.validated]], carrying its alias
  * already NORMALIZED (lowercase, via Names.normalizeOrError). The constructor is private to the
  * package so the only way to obtain one is through `validated`; `IcebergSetupSql.render` accepts
  * nothing else. That is what turns render's former comment-only precondition into a type.
  */
final case class ValidatedIcebergConfig private[iceberg] (config: IcebergRestConfig, alias: String)

object IcebergRestConfig:
  /** DuckDB's built-in catalogs. An alias colliding with one of these would shadow it. */
  val ReservedAliases: Set[String] = ai.starlake.quack.model.DuckDbCatalogs.Builtins

  private[iceberg] def isSet(o: Option[String]): Boolean = o.exists(_.trim.nonEmpty)

  private val SecretPlaceholder = "\\{\\{secret\\.[A-Za-z0-9_]+\\}\\}".r

  /** `fieldName`, when set to a non-blank value, must be a `{{secret.NAME}}` placeholder rather
    * than a literal - see the class scaladoc.
    */
  private def placeholderErrors(fieldName: String, value: Option[String]): List[String] =
    value.map(_.trim).filter(_.nonEmpty) match
      case Some(v) if SecretPlaceholder.matches(v) => Nil
      case Some(_)                                 =>
        List(
          s"$fieldName must be a secret placeholder of the form {{secret.NAME}}, not a literal value"
        )
      case None => Nil

  /** `fieldName`, when set to a value containing '{{', must have every '{{' accounted for by a
    * WELL-FORMED `{{secret.NAME}}` placeholder - a stray or malformed brace left over would pass
    * through [[ai.starlake.quack.ondemand.federation.FederationBlobBuilder]] unresolved and fail
    * the whole tenant-db federation blob. Unlike [[placeholderErrors]], a literal value with no
    * brace at all is fine here - these fields, unlike clientSecret / token, are not required to be
    * placeholders, and a placeholder MAY be embedded anywhere in a larger value (e.g. a uri or
    * warehouse that is partly literal, partly secret).
    *
    * This mirrors `FederationBlobBuilder.substitute` exactly rather than approximating it: the
    * builder resolves every well-formed `{{secret.NAME}}` wherever it appears in the rendered SQL
    * (`SecretRegex.replaceAllIn`), then fails only if a `{{` still remains. Stripping every
    * well-formed placeholder and checking what is left is that same test, so a value this accepts
    * is one the builder can actually resolve.
    */
  private[iceberg] def strayBraceErrors(fieldName: String, value: Option[String]): List[String] =
    value.filter(_.contains("{{")) match
      case None    => Nil
      case Some(v) =>
        if SecretPlaceholder.replaceAllIn(v, "").contains("{{") then
          List(
            s"$fieldName has a stray or malformed '{{': a well-formed {{secret.NAME}} is " +
              "substituted at node spawn, but anything else survives into the SQL and trips " +
              "FederationBlobBuilder's stray-placeholder check, failing the entire tenant-db " +
              "federation blob"
          )
        else Nil

  // Absent optional wire fields fall back to the case-class defaults: plain deriveCodec is strict
  // (it rejects a missing field even when the case class has a default), which would break every
  // stored row the day an eleventh field is added. ConfiguredCodec derives against the case class
  // defaults instead, matching Dtos.scala's convention for persisted types.
  private given Configuration = Configuration.default.withDefaults

  given Codec[IcebergRestConfig] = ConfiguredCodec.derived

  def fromJson(s: String): Either[String, IcebergRestConfig] =
    io.circe.parser.decode[IcebergRestConfig](s).left.map(_.getMessage)

  /** Normalize the alias (Names rule: lowercase, 1..63 chars, identifier pattern), run every
    * validation rule against the normalized alias, and wrap. Left carries every error at once.
    *
    * `extraReserved` extends [[ReservedAliases]] (DuckDB's builtins) with whatever else the alias
    * must not collide with. Callers SHOULD pass the tenant-db's own DuckDB catalog alias plus every
    * sibling federated alias - the set Main.scala's `attachedCatalogsOf` computes - because an
    * alias colliding with an already-attached catalog produces `ATTACH 'x' AS "name"` for a name
    * DuckDB already holds, i.e. `Binder Error: Failed to attach database: database with name "name"
    * already exists`. The DuckDB CLI reading piped stdin does not bail on that error, so the node
    * comes up with a partly-failed federation blob and no loud signal. Comparison is
    * case-insensitive (delegated to `validate`, which already lowercases via `equalsIgnoreCase`),
    * so an entry in any case still blocks the normalized (lowercase) alias.
    */
  def validated(
      cfg: IcebergRestConfig,
      alias: String,
      extraReserved: Set[String] = Set.empty
  ): Either[List[String], ValidatedIcebergConfig] =
    Names.normalizeOrError(alias, "alias") match
      case Left(err)   => Left(List(err))
      case Right(norm) =>
        val errs = cfg.validate(norm, ReservedAliases ++ extraReserved)
        if errs.isEmpty then Right(ValidatedIcebergConfig(cfg, norm)) else Left(errs)
