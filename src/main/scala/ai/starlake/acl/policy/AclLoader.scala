package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{AclPolicy, Grant, GrantTarget, Principal}
import cats.data.{Validated, ValidatedNel}
import cats.syntax.all.*
import io.circe.{Decoder, HCursor}
import io.circe.yaml.v12.parser as yamlParser

import java.time.Instant

object AclLoader:

  // ---------------------------------------------------------------------------
  // Raw intermediate types for YAML decoding
  // ---------------------------------------------------------------------------

  private case class RawGrant(target: String, principals: List[String], authorized: Boolean, expires: Option[String])

  private given Decoder[RawGrant] = Decoder.instance { (c: HCursor) =>
    for
      target     <- c.get[String]("target")
      principals <- c.get[List[String]]("principals")
      authorized <- c.getOrElse[Boolean]("authorized")(false)
      expires    <- c.getOrElse[Option[String]]("expires")(None)
    yield RawGrant(target, principals, authorized, expires)
  }

  private case class RawAclDocument(grants: List[RawGrant], mode: String)

  private given Decoder[RawAclDocument] = Decoder.instance { (c: HCursor) =>
    for
      grants <- c.get[List[RawGrant]]("grants")
      mode   <- c.getOrElse[String]("mode")("strict")
    yield RawAclDocument(grants, mode)
  }

  // ---------------------------------------------------------------------------
  // Validation functions
  // ---------------------------------------------------------------------------

  private def validateTarget(target: String, grantIndex: Int): ValidatedNel[AclError, GrantTarget] =
    target.split("\\.", -1).toList match
      case db :: Nil if db.nonEmpty =>
        GrantTarget.database(db).validNel
      case db :: schema :: Nil if db.nonEmpty && schema.nonEmpty =>
        GrantTarget.schema(db, schema).validNel
      case db :: schema :: table :: Nil if db.nonEmpty && schema.nonEmpty && table.nonEmpty =>
        GrantTarget.table(db, schema, table).validNel
      case _ =>
        AclError
          .InvalidTarget(
            target,
            "must be database, database.schema, or database.schema.table (1-3 dot-separated non-empty parts)",
            grantIndex
          )
          .invalidNel

  private def validatePrincipal(raw: String, grantIndex: Int): ValidatedNel[AclError, Principal] =
    raw.split(":", 2).toList match
      case "user" :: name :: Nil if name.nonEmpty =>
        Principal.user(name).validNel
      case "group" :: name :: Nil if name.nonEmpty =>
        Principal.group(name).validNel
      case _ =>
        AclError
          .InvalidPrincipal(
            raw,
            "must be 'user:name' or 'group:name' with non-empty name",
            grantIndex
          )
          .invalidNel

  private def validatePrincipals(
      principals: List[String],
      grantIndex: Int
  ): ValidatedNel[AclError, List[Principal]] =
    if principals.isEmpty then AclError.EmptyPrincipals(grantIndex).invalidNel
    else principals.traverse(p => validatePrincipal(p, grantIndex))

  private def validateExpires(
      raw: Option[String],
      grantIndex: Int
  ): ValidatedNel[AclError, Option[Instant]] =
    raw match
      case None => None.validNel
      case Some(s) =>
        try Some(Instant.parse(s)).validNel
        catch
          case _: java.time.format.DateTimeParseException =>
            AclError.InvalidExpires(s, grantIndex).invalidNel

  private def validateGrant(raw: RawGrant, grantIndex: Int): ValidatedNel[AclError, Grant] =
    (
      validateTarget(raw.target, grantIndex),
      validatePrincipals(raw.principals, grantIndex),
      validateExpires(raw.expires, grantIndex)
    ).mapN { (target, principals, expires) =>
      Grant(target, principals, expires = expires, authorized = raw.authorized)
    }

  private def validateMode(raw: String): ValidatedNel[AclError, ResolutionMode] =
    raw.toLowerCase match
      case "strict"       => ResolutionMode.Strict.validNel
      case "permissive"   => ResolutionMode.Permissive.validNel
      case "defaultallow" => ResolutionMode.DefaultAllow.validNel
      case other          => AclError.InvalidMode(other).invalidNel

  private def validateDocument(doc: RawAclDocument): ValidatedNel[AclError, AclPolicy] =
    if doc.grants.isEmpty then AclError.EmptyPolicy().invalidNel
    else
      doc.grants.zipWithIndex
        .traverse { case (raw, idx) => validateGrant(raw, idx) }
        .andThen { grants =>
          validateMode(doc.mode).map(mode => AclPolicy(grants, mode))
        }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Load and validate a YAML ACL string using system environment variables. */
  def load(yaml: String): ValidatedNel[AclError, AclPolicy] =
    loadWithEnv(yaml, name => Option(System.getenv(name)))

  /** Load and validate a YAML ACL string with a custom environment resolver. */
  def loadWithEnv(
      yaml: String,
      env: String => Option[String]
  ): ValidatedNel[AclError, AclPolicy] =
    EnvVarResolver.resolve(yaml, env).andThen { resolved =>
      yamlParser.parse(resolved) match
        case Left(err) =>
          Validated.invalidNel(AclError.YamlParseError(err.getMessage))
        case Right(json) =>
          json.as[RawAclDocument] match
            case Left(err) =>
              Validated.invalidNel(AclError.YamlParseError(err.getMessage))
            case Right(doc) =>
              validateDocument(doc)
    }

  /** Load and merge multiple YAML ACL strings using system environment variables. */
  def loadAll(yamls: List[String]): ValidatedNel[AclError, AclPolicy] =
    loadAllWithEnv(yamls, name => Option(System.getenv(name)))

  /** Load and merge multiple YAML ACL strings with a custom environment resolver. */
  def loadAllWithEnv(
      yamls: List[String],
      env: String => Option[String]
  ): ValidatedNel[AclError, AclPolicy] =
    if yamls.isEmpty then AclError.EmptyPolicy().invalidNel
    else
      yamls
        .traverse(y => loadWithEnv(y, env))
        .map { policies =>
          val mergedGrants = policies.flatMap(_.grants)
          val mode         = policies.headOption.map(_.mode).getOrElse(ResolutionMode.Strict)
          AclPolicy(mergedGrants, mode)
        }
