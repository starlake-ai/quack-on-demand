package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.SupervisorError
import sttp.model.StatusCode

/** REST status + code for the supervisor errors that carry a fixed public code, so every handler
  * answers them identically instead of folding them into its own catch-all.
  */
object SupervisorErrorHttp:
  def special(e: SupervisorError): Option[(StatusCode, ErrorResponse)] = e match
    case SupervisorError.BuiltinProtected(m) =>
      Some(StatusCode.Conflict -> ErrorResponse("builtin_protected", m))
    case SupervisorError.ReservedName(m) =>
      Some(StatusCode.BadRequest -> ErrorResponse("reserved_name", m))
    case SupervisorError.InvalidMembership(code, m) =>
      Some(StatusCode.BadRequest -> ErrorResponse(code, m))
    case SupervisorError.DatabaseNameTaken(m) =>
      Some(StatusCode.Conflict -> ErrorResponse("database_name_taken", m))
    case SupervisorError.OwnLoginRequired(m) =>
      Some(StatusCode.BadRequest -> ErrorResponse("own_login_required", m))
    case SupervisorError.DbNameFixed(m) =>
      Some(StatusCode.BadRequest -> ErrorResponse("db_name_fixed", m))
    case SupervisorError.CatalogDatabaseMissing(m) =>
      Some(StatusCode.BadRequest -> ErrorResponse("catalog_database_missing", m))
    case SupervisorError.SuperuserChoice(m) =>
      Some(StatusCode.Forbidden -> ErrorResponse("superuser_required", m))
    case SupervisorError.DataPathTaken(m) =>
      Some(StatusCode.Conflict -> ErrorResponse("data_path_taken", m))
    case _ => None
