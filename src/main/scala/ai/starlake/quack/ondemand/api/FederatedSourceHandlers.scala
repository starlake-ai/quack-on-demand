package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import ai.starlake.quack.ondemand.state.FederatedSourceOps
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for FederatedSource + FederatedSecret rows.
  *
  * @param fedStore
  *   the federation store (any [[FederatedSourceOps]] implementation)
  * @param resolver
  *   resolves (tenantName, tenantDbName) to the surrogate tenantDbId, or None if not found
  * @param tenantIdResolver
  *   resolves a tenant name / display-name to its surrogate tenant id for audit attribution
  */
final class FederatedSourceHandlers(
    fedStore: FederatedSourceOps,
    resolver: (String, String) => Option[String],
    tenantIdResolver: String => Option[String] = _ => None,
    audit: AuditRecorder = AuditRecorder.noop
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val REDACTED = "***REDACTED***"

  // ---- helpers ------------------------------------------------------------

  private def resolveTenantDbId(
      tenantName: String,
      tenantDbName: String
  ): Either[(StatusCode, ErrorResponse), String] =
    resolver(tenantName, tenantDbName) match
      case Some(id) => Right(id)
      case None     =>
        Left(
          StatusCode.NotFound -> ErrorResponse(
            "not_found",
            s"tenant-db '$tenantDbName' not found in tenant '$tenantName'"
          )
        )

  private def toSourceResponse(s: FederatedSource): FederatedSourceResponse =
    FederatedSourceResponse(
      id = s.id,
      tenantDbId = s.tenantDbId,
      alias = s.alias,
      setupSql = s.setupSql,
      description = s.description,
      disabled = s.disabled
    )

  private def toSecretResponse(s: FederatedSecret): FederatedSecretResponse =
    FederatedSecretResponse(
      id = s.id,
      federatedSourceId = s.federatedSourceId,
      name = s.name,
      value = s.value.map(_ => REDACTED),
      externalRef = s.externalRef
    )

  // ---- FederatedSource CRUD -----------------------------------------------

  def createSource(
      tenantName: String,
      tenantDbName: String,
      req: FederatedSourceCreateRequest,
      apiKey: Option[String]
  ): Out[FederatedSourceResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          // Try an upsert by alias: if one already exists reuse its id.
          val existing = fedStore.getSource(tenantDbId, req.alias)
          val id = existing.map(_.id).getOrElse(ai.starlake.quack.model.Names.newSurrogateId("fs"))
          fedStore.upsertSource(
            FederatedSource(
              id = id,
              tenantDbId = tenantDbId,
              alias = req.alias,
              setupSql = req.setupSql,
              description = req.description,
              disabled = req.disabled
            )
          )
          // NEVER include setupSql in detail (may contain connection strings).
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.FederationSourceUpsert,
            "ok",
            tenant = tenantIdResolver(tenantName),
            target = Some(req.alias)
          )
          Right(
            toSourceResponse(
              fedStore
                .getSource(tenantDbId, req.alias)
                .getOrElse(
                  FederatedSource(
                    id,
                    tenantDbId,
                    req.alias,
                    req.setupSql,
                    req.description,
                    req.disabled
                  )
                )
            )
          )
    }

  def listSources(tenantName: String, tenantDbName: String): Out[FederatedSourceListResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          Right(FederatedSourceListResponse(fedStore.listSources(tenantDbId).map(toSourceResponse)))
    }

  def getSource(
      tenantName: String,
      tenantDbName: String,
      alias: String
  ): Out[FederatedSourceResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case Some(s) => Right(toSourceResponse(s))
            case None    =>
              Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
    }

  def deleteSource(
      tenantName: String,
      tenantDbName: String,
      alias: String,
      apiKey: Option[String]
  ): Out[Unit] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None =>
              Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              fedStore.deleteSource(s.id)
              audit.rest(
                apiKey,
                "control-plane",
                AuditActions.FederationSourceDelete,
                "ok",
                tenant = tenantIdResolver(tenantName),
                target = Some(alias)
              )
              Right(())
    }

  // ---- FederatedSecret CRUD -----------------------------------------------

  def listSecrets(
      tenantName: String,
      tenantDbName: String,
      alias: String
  ): Out[FederatedSecretListResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None =>
              Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              Right(FederatedSecretListResponse(fedStore.listSecrets(s.id).map(toSecretResponse)))
    }

  def upsertSecret(
      tenantName: String,
      tenantDbName: String,
      alias: String,
      req: FederatedSecretUpsertRequest,
      apiKey: Option[String]
  ): Out[FederatedSecretResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None =>
              Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              // Validate exactly one of value/externalRef is set
              (req.value, req.externalRef) match
                case (Some(_), Some(_)) =>
                  Left(
                    StatusCode.BadRequest -> ErrorResponse(
                      "invalid",
                      "exactly one of value/externalRef must be set"
                    )
                  )
                case (None, None) =>
                  Left(
                    StatusCode.BadRequest -> ErrorResponse(
                      "invalid",
                      "one of value or externalRef must be provided"
                    )
                  )
                case _ =>
                  val existing = fedStore.getSecret(s.id, req.name)
                  val id       =
                    existing
                      .map(_.id)
                      .getOrElse(ai.starlake.quack.model.Names.newSurrogateId("fsec"))
                  val sec = FederatedSecret(
                    id = id,
                    federatedSourceId = s.id,
                    name = req.name,
                    value = req.value,
                    externalRef = req.externalRef
                  )
                  fedStore.upsertSecret(sec)
                  // NEVER include value or externalRef in detail (secret material).
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.FederationSecretUpsert,
                    "ok",
                    tenant = tenantIdResolver(tenantName),
                    target = Some(s"$alias/${req.name}")
                  )
                  Right(toSecretResponse(sec))
    }

  def deleteSecret(
      tenantName: String,
      tenantDbName: String,
      alias: String,
      name: String,
      apiKey: Option[String]
  ): Out[Unit] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e)           => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None =>
              Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              fedStore.getSecret(s.id, name) match
                case None =>
                  Left(
                    StatusCode.NotFound -> ErrorResponse("not_found", s"secret '$name' not found")
                  )
                case Some(_) =>
                  fedStore.deleteSecret(s.id, name)
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.FederationSecretDelete,
                    "ok",
                    tenant = tenantIdResolver(tenantName),
                    target = Some(s"$alias/$name")
                  )
                  Right(())
    }
