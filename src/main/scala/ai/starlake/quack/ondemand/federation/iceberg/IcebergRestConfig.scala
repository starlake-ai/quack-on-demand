package ai.starlake.quack.ondemand.federation.iceberg

import io.circe.generic.semiauto.deriveCodec
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
  * Credential-bearing fields hold `{{secret.NAME}}` placeholders, never literal values: they are
  * resolved at node spawn by [[ai.starlake.quack.ondemand.federation.FederationBlobBuilder]]
  * against `qodstate_federated_secret`, exactly as operator-written federation SQL is. That is why
  * this type is safe to return over REST and needs no redaction.
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

    if !IcebergRestConfig.AliasPattern.matches(alias) then
      errs += s"alias '$alias' must be a plain SQL identifier (letters, digits, underscore; " +
        "not starting with a digit)"
    if reservedAliases.exists(_.equalsIgnoreCase(alias)) then errs += s"alias '$alias' is reserved"

    if warehouse.trim.isEmpty then errs += "warehouse is required"

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
      case Some(other) =>
        if credentialFieldsSet then
          errs += s"authType '${other.wire}' takes no clientId, clientSecret, oauth2 or token fields"
      case None =>
        if credentialFieldsSet then
          errs += "endpointType takes no clientId, clientSecret, oauth2 or token fields"

    errs.result()

object IcebergRestConfig:
  /** DuckDB's built-in catalogs. An alias colliding with one of these would shadow it. */
  val ReservedAliases: Set[String] = Set("memory", "system", "temp")

  private val AliasPattern = "[A-Za-z_][A-Za-z0-9_]*".r

  private[iceberg] def isSet(o: Option[String]): Boolean = o.exists(_.trim.nonEmpty)

  given Codec[IcebergRestConfig] = deriveCodec

  def fromJson(s: String): Either[String, IcebergRestConfig] =
    io.circe.parser.decode[IcebergRestConfig](s).left.map(_.getMessage)
