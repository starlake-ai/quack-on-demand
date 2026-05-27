package ai.starlake.acl

import io.circe.{Encoder, Json}
import io.circe.syntax.*

sealed trait AclError:
  def message: String
  def toJson: Json = this.asJson(using AclError.aclErrorEncoder)

object AclError:

  // ---------------------------------------------------------------------------
  // Existing subtypes (from policy package)
  // ---------------------------------------------------------------------------

  case class YamlParseError(detail: String) extends AclError:
    def message: String = s"YAML parse error: $detail"

  case class InvalidTarget(target: String, reason: String, grantIndex: Int) extends AclError:
    def message: String = s"Invalid target '$target' at grant #${grantIndex + 1}: $reason"

  case class InvalidPrincipal(principal: String, reason: String, grantIndex: Int) extends AclError:
    def message: String = s"Invalid principal '$principal' at grant #${grantIndex + 1}: $reason"

  case class UnresolvedVariable(varName: String) extends AclError:
    def message: String = s"Unresolved environment variable: $${$varName}"

  case class EmptyPrincipals(grantIndex: Int) extends AclError:
    def message: String = s"Empty principals list at grant #${grantIndex + 1}"

  case class EmptyPolicy() extends AclError:
    def message: String = "ACL policy must contain at least one grant"

  case class InvalidMode(value: String) extends AclError:
    def message: String = s"Invalid resolution mode '$value': must be 'strict', 'permissive', or 'defaultAllow'"

  // ---------------------------------------------------------------------------
  // New subtypes for public API
  // ---------------------------------------------------------------------------

  case class SqlParseError(sql: String, detail: String, cause: Option[Throwable] = None) extends AclError:
    def message: String = s"SQL parse error: $detail"

  case class ConfigError(detail: String, cause: Option[Throwable] = None) extends AclError:
    def message: String = s"Configuration error: $detail"

  case class ResolutionError(detail: String, cause: Option[Throwable] = None) extends AclError:
    def message: String = s"Resolution error: $detail"

  case class InternalError(detail: String, cause: Option[Throwable] = None) extends AclError:
    def message: String = s"Internal error: $detail"

  case class TenantNotFound(tenantId: String) extends AclError:
    def message: String = s"Tenant not found: $tenantId"

  case class InvalidExpires(value: String, grantIndex: Int) extends AclError:
    def message: String = s"Invalid expires '$value' at grant #${grantIndex + 1}: must be ISO-8601 Instant (e.g., 2025-12-31T23:59:59Z)"

  case class StoreError(detail: String, cause: Option[Throwable] = None) extends AclError:
    def message: String = s"Store error: $detail"

  // ---------------------------------------------------------------------------
  // JSON serialization (MODEL-002 pattern: manual Encoder)
  // ---------------------------------------------------------------------------

  given aclErrorEncoder: Encoder[AclError] = Encoder.instance {
    case e: YamlParseError =>
      Json.obj(
        "type"   -> "yamlParseError".asJson,
        "detail" -> e.message.asJson
      )

    case e: InvalidTarget =>
      Json.obj(
        "type"       -> "invalidTarget".asJson,
        "detail"     -> e.message.asJson,
        "target"     -> e.target.asJson,
        "reason"     -> e.reason.asJson,
        "grantIndex" -> e.grantIndex.asJson
      )

    case e: InvalidPrincipal =>
      Json.obj(
        "type"       -> "invalidPrincipal".asJson,
        "detail"     -> e.message.asJson,
        "principal"  -> e.principal.asJson,
        "reason"     -> e.reason.asJson,
        "grantIndex" -> e.grantIndex.asJson
      )

    case e: UnresolvedVariable =>
      Json.obj(
        "type"    -> "unresolvedVariable".asJson,
        "detail"  -> e.message.asJson,
        "varName" -> e.varName.asJson
      )

    case e: EmptyPrincipals =>
      Json.obj(
        "type"       -> "emptyPrincipals".asJson,
        "detail"     -> e.message.asJson,
        "grantIndex" -> e.grantIndex.asJson
      )

    case _: EmptyPolicy =>
      Json.obj(
        "type"   -> "emptyPolicy".asJson,
        "detail" -> "ACL policy must contain at least one grant".asJson
      )

    case e: InvalidMode =>
      Json.obj(
        "type"   -> "invalidMode".asJson,
        "detail" -> e.message.asJson,
        "value"  -> e.value.asJson
      )

    case e: SqlParseError =>
      Json.obj(
        "type"   -> "sqlParseError".asJson,
        "detail" -> e.message.asJson,
        "sql"    -> e.sql.asJson
      )

    case e: ConfigError =>
      Json.obj(
        "type"   -> "configError".asJson,
        "detail" -> e.message.asJson
      )

    case e: ResolutionError =>
      Json.obj(
        "type"   -> "resolutionError".asJson,
        "detail" -> e.message.asJson
      )

    case e: InternalError =>
      Json.obj(
        "type"   -> "internalError".asJson,
        "detail" -> e.message.asJson
      )

    case e: TenantNotFound =>
      Json.obj(
        "type"     -> "tenantNotFound".asJson,
        "detail"   -> e.message.asJson,
        "tenantId" -> e.tenantId.asJson
      )

    case e: InvalidExpires =>
      Json.obj(
        "type"       -> "invalidExpires".asJson,
        "detail"     -> e.message.asJson,
        "value"      -> e.value.asJson,
        "grantIndex" -> e.grantIndex.asJson
      )

    case e: StoreError =>
      Json.obj(
        "type"   -> "storeError".asJson,
        "detail" -> e.message.asJson
      )
  }
