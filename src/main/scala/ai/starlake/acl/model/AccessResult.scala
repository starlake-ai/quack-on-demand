package ai.starlake.acl.model

import io.circe.{Encoder, Json}
import io.circe.syntax.*
import java.time.Instant

enum Decision {
  case Allowed
  case Denied
}

enum GrantType:
  case Regular
  case Authorized
  case UnknownButAllowed

final case class TableAccess(
    table: TableRef,
    decision: Decision,
    matchedGrant: Option[Grant],
    denyReason: Option[DenyReason],
    grantType: Option[GrantType] = None,
    isView: Boolean = false,
    warnings: List[String] = Nil
)

final case class ViewResolution(
    view: TableRef,
    underlyingTables: List[TableRef]
)

final case class AccessResult(
    decision: Decision,
    sql: String,
    user: UserIdentity,
    timestamp: Instant,
    tableAccesses: List[TableAccess],
    viewResolutions: List[ViewResolution],
    resolutionMap: Map[TableRef, Set[TableRef]] = Map.empty,
    tenantId: Option[TenantId] = None,
    usedStaleGrants: Boolean = false
) {

  def isAllowed: Boolean = decision == Decision.Allowed

  def deniedTables: List[TableRef] = tableAccesses.filter(_.decision == Decision.Denied).map(_.table)

  def allowedTables: List[TableRef] = tableAccesses.filter(_.decision == Decision.Allowed).map(_.table)

  def allTables: List[TableRef] = tableAccesses.map(_.table)

  def toJson: Json = this.asJson(using AccessResult.accessResultEncoder)
}

object AccessResult {

  given decisionEncoder: Encoder[Decision] = Encoder.encodeString.contramap {
    case Decision.Allowed => "allowed"
    case Decision.Denied  => "denied"
  }

  given grantTypeEncoder: Encoder[GrantType] = Encoder.encodeString.contramap {
    case GrantType.Regular          => "regular"
    case GrantType.Authorized       => "authorized"
    case GrantType.UnknownButAllowed => "unknownButAllowed"
  }

  given tableRefEncoder: Encoder[TableRef] = Encoder.instance { ref =>
    Json.obj(
      "database"  -> ref.database.asJson,
      "schema"    -> ref.schema.asJson,
      "table"     -> ref.table.asJson,
      "canonical" -> ref.canonical.asJson
    )
  }

  given userIdentityEncoder: Encoder[UserIdentity] = Encoder.instance { user =>
    Json.obj(
      "name"   -> user.name.asJson,
      "groups" -> user.groups.asJson
    )
  }

  given denyReasonEncoder: Encoder[DenyReason] = Encoder.instance {
    case DenyReason.ParseError(message) =>
      Json.obj("type" -> "parseError".asJson, "message" -> message.asJson)
    case DenyReason.NoMatchingGrant(table, user) =>
      Json.obj(
        "type"  -> "noMatchingGrant".asJson,
        "table" -> table.asJson(using tableRefEncoder),
        "user"  -> user.asJson(using userIdentityEncoder)
      )
    case DenyReason.ViewResolutionCycle(chain) =>
      Json.obj(
        "type"  -> "viewResolutionCycle".asJson,
        "chain" -> chain.asJson(using Encoder.encodeList(using tableRefEncoder))
      )
    case DenyReason.UnknownView(viewRef) =>
      Json.obj("type" -> "unknownView".asJson, "viewRef" -> viewRef.asJson(using tableRefEncoder))
    case DenyReason.UnsupportedStatement(statementType) =>
      Json.obj("type" -> "unsupportedStatement".asJson, "statementType" -> statementType.asJson)
    case DenyReason.UnqualifiedTable(tableName, missingPart) =>
      Json.obj(
        "type"        -> "unqualifiedTable".asJson,
        "tableName"   -> tableName.asJson,
        "missingPart" -> missingPart.asJson
      )
    case DenyReason.ViewParseError(viewRef, message) =>
      Json.obj(
        "type"    -> "viewParseError".asJson,
        "viewRef" -> viewRef.asJson(using tableRefEncoder),
        "message" -> message.asJson
      )
    case DenyReason.CallbackError(table, message) =>
      Json.obj(
        "type"    -> "callbackError".asJson,
        "table"   -> table.asJson(using tableRefEncoder),
        "message" -> message.asJson
      )

    case DenyReason.ExpiredGrant(table, user, expiredAt) =>
      Json.obj(
        "type"      -> "expiredGrant".asJson,
        "table"     -> table.asJson(using tableRefEncoder),
        "user"      -> user.asJson(using userIdentityEncoder),
        "expiredAt" -> expiredAt.toString.asJson
      )
    case DenyReason.MaxViewDepthExceeded(path) =>
      Json.obj(
        "type" -> "maxViewDepthExceeded".asJson,
        "path" -> path.asJson(using Encoder.encodeList(using tableRefEncoder))
      )
  }

  given grantTargetEncoder: Encoder[GrantTarget] = Encoder.instance {
    case GrantTarget.All =>
      Json.obj("type" -> "all".asJson)
    case GrantTarget.Database(database) =>
      Json.obj("type" -> "database".asJson, "database" -> database.asJson)
    case GrantTarget.Schema(database, schema) =>
      Json.obj("type" -> "schema".asJson, "database" -> database.asJson, "schema" -> schema.asJson)
    case GrantTarget.Table(database, schema, table) =>
      Json.obj(
        "type"     -> "table".asJson,
        "database" -> database.asJson,
        "schema"   -> schema.asJson,
        "table"    -> table.asJson
      )
  }

  given principalEncoder: Encoder[Principal] = Encoder.encodeString.contramap {
    case Principal.User(name)  => s"user:$name"
    case Principal.Group(name) => s"group:$name"
    case Principal.AllUsers    => "allUsers"
  }

  given grantEncoder: Encoder[Grant] = Encoder.instance { grant =>
    val base = Json.obj(
      "target"     -> grant.target.asJson,
      "principals" -> grant.principals.asJson,
      "authorized" -> grant.authorized.asJson
    )
    grant.expires match {
      case Some(exp) => base.deepMerge(Json.obj("expires" -> exp.toString.asJson))
      case None      => base
    }
  }

  given tableAccessEncoder: Encoder[TableAccess] = Encoder.instance { ta =>
    val base = Json.obj(
      "table"        -> ta.table.asJson,
      "decision"     -> ta.decision.asJson,
      "matchedGrant" -> ta.matchedGrant.asJson,
      "denyReason"   -> ta.denyReason.asJson,
      "grantType"    -> ta.grantType.asJson,
      "isView"       -> ta.isView.asJson
    )
    if ta.warnings.nonEmpty then
      base.deepMerge(Json.obj("warnings" -> ta.warnings.asJson))
    else base
  }

  given viewResolutionEncoder: Encoder[ViewResolution] = Encoder.instance { vr =>
    Json.obj(
      "view"             -> vr.view.asJson,
      "underlyingTables" -> vr.underlyingTables.asJson
    )
  }

  private given resolutionMapEntryEncoder: Encoder[(TableRef, Set[TableRef])] = Encoder.instance { case (view, baseTables) =>
    Json.obj(
      "view"       -> view.asJson,
      "baseTables" -> baseTables.toList.asJson
    )
  }

  given accessResultEncoder: Encoder[AccessResult] = Encoder.instance { ar =>
    val base = Json.obj(
      "decision"  -> ar.decision.asJson,
      "sql"       -> ar.sql.asJson,
      "user"      -> ar.user.asJson,
      "timestamp" -> ar.timestamp.toString.asJson,
      "tables" -> Json.obj(
        "allowed" -> ar.allowedTables.asJson,
        "denied"  -> ar.deniedTables.asJson
      ),
      "tableAccesses"   -> ar.tableAccesses.asJson,
      "viewResolutions" -> ar.viewResolutions.asJson
    )
    val withResolutionMap = if ar.resolutionMap.nonEmpty then
      base.deepMerge(Json.obj("resolutionMap" -> ar.resolutionMap.toList.asJson))
    else base
    val withTenantId = ar.tenantId match {
      case Some(tid) => withResolutionMap.deepMerge(Json.obj("tenantId" -> tid.canonical.asJson))
      case None      => withResolutionMap
    }
    if ar.usedStaleGrants then
      withTenantId.deepMerge(Json.obj("usedStaleGrants" -> true.asJson))
    else withTenantId
  }
}
