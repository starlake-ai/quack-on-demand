package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import ai.starlake.quack.ondemand.state.FederatedSourceStore
import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*
import io.circe.yaml.v12.{Printer, parser}
import sttp.model.StatusCode

import java.util.UUID

/** REST handlers for FederatedSource + FederatedSecret rows.
  *
  * @param fedStore the federation store
  * @param resolver resolves (tenantName, tenantDbName) to the surrogate tenantDbId, or None if not found
  */
final class FederatedSourceHandlers(
    fedStore: FederatedSourceStore,
    resolver: (String, String) => Option[String]
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  private val REDACTED = "***REDACTED***"

  // ---- internal YAML-only shapes ------------------------------------------

  private final case class FederationYamlSecret(
      name:        String,
      value:       Option[String],
      externalRef: Option[String]
  )

  private final case class FederationYamlSource(
      alias:       String,
      description: Option[String],
      disabled:    Boolean,
      setupSql:    String,
      secrets:     List[FederationYamlSecret]
  )

  private final case class FederationYamlPayload(
      federatedSources: List[FederationYamlSource]
  )

  private given Codec[FederationYamlSecret]  = deriveCodec
  private given Codec[FederationYamlSource]  = deriveCodec
  private given Codec[FederationYamlPayload] = deriveCodec

  // ---- helpers ------------------------------------------------------------

  private def resolveTenantDbId(tenantName: String, tenantDbName: String): Either[(StatusCode, ErrorResponse), String] =
    resolver(tenantName, tenantDbName) match
      case Some(id) => Right(id)
      case None     =>
        Left(StatusCode.NotFound -> ErrorResponse(
          "not_found",
          s"tenant-db '$tenantDbName' not found in tenant '$tenantName'"
        ))

  private def toSourceResponse(s: FederatedSource): FederatedSourceResponse =
    FederatedSourceResponse(
      id          = s.id,
      tenantDbId  = s.tenantDbId,
      alias       = s.alias,
      setupSql    = s.setupSql,
      description = s.description,
      disabled    = s.disabled
    )

  private def toSecretResponse(s: FederatedSecret): FederatedSecretResponse =
    FederatedSecretResponse(
      id                = s.id,
      federatedSourceId = s.federatedSourceId,
      name              = s.name,
      value             = s.value.map(_ => REDACTED),
      externalRef       = s.externalRef
    )

  // ---- FederatedSource CRUD -----------------------------------------------

  def createSource(
      tenantName:   String,
      tenantDbName: String,
      req:          FederatedSourceCreateRequest
  ): Out[FederatedSourceResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          // Try an upsert by alias: if one already exists reuse its id.
          val existing = fedStore.getSource(tenantDbId, req.alias)
          val id = existing.map(_.id).getOrElse(s"fs-${UUID.randomUUID().toString.take(8)}")
          fedStore.upsertSource(FederatedSource(
            id          = id,
            tenantDbId  = tenantDbId,
            alias       = req.alias,
            setupSql    = req.setupSql,
            description = req.description,
            disabled    = req.disabled
          ))
          Right(toSourceResponse(
            fedStore.getSource(tenantDbId, req.alias).getOrElse(
              FederatedSource(id, tenantDbId, req.alias, req.setupSql, req.description, req.disabled)
            )
          ))
    }

  def listSources(tenantName: String, tenantDbName: String): Out[FederatedSourceListResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          Right(FederatedSourceListResponse(fedStore.listSources(tenantDbId).map(toSourceResponse)))
    }

  def getSource(tenantName: String, tenantDbName: String, alias: String): Out[FederatedSourceResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case Some(s) => Right(toSourceResponse(s))
            case None    => Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
    }

  def deleteSource(tenantName: String, tenantDbName: String, alias: String): Out[Unit] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None    => Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              fedStore.deleteSource(s.id)
              Right(())
    }

  // ---- FederatedSecret CRUD -----------------------------------------------

  def listSecrets(tenantName: String, tenantDbName: String, alias: String): Out[FederatedSecretListResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None    => Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              Right(FederatedSecretListResponse(fedStore.listSecrets(s.id).map(toSecretResponse)))
    }

  def upsertSecret(
      tenantName:   String,
      tenantDbName: String,
      alias:        String,
      req:          FederatedSecretUpsertRequest
  ): Out[FederatedSecretResponse] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None    => Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              // Validate exactly one of value/externalRef is set
              (req.value, req.externalRef) match
                case (Some(_), Some(_)) =>
                  Left(StatusCode.BadRequest -> ErrorResponse(
                    "invalid", "exactly one of value/externalRef must be set"
                  ))
                case (None, None) =>
                  Left(StatusCode.BadRequest -> ErrorResponse(
                    "invalid", "one of value or externalRef must be provided"
                  ))
                case _ =>
                  val existing = fedStore.getSecret(s.id, req.name)
                  val id = existing.map(_.id).getOrElse(s"fsec-${UUID.randomUUID().toString.take(8)}")
                  val sec = FederatedSecret(
                    id                = id,
                    federatedSourceId = s.id,
                    name              = req.name,
                    value             = req.value,
                    externalRef       = req.externalRef
                  )
                  fedStore.upsertSecret(sec)
                  Right(toSecretResponse(sec))
    }

  def deleteSecret(tenantName: String, tenantDbName: String, alias: String, name: String): Out[Unit] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          fedStore.getSource(tenantDbId, alias) match
            case None    => Left(StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found"))
            case Some(s) =>
              fedStore.getSecret(s.id, name) match
                case None    => Left(StatusCode.NotFound -> ErrorResponse("not_found", s"secret '$name' not found"))
                case Some(_) =>
                  fedStore.deleteSecret(s.id, name)
                  Right(())
    }

  // ---- YAML export/import -------------------------------------------------

  def exportYaml(tenantName: String, tenantDbName: String): Out[String] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          val srcs = fedStore.listSources(tenantDbId)
          val payload = FederationYamlPayload(
            federatedSources = srcs.map { s =>
              FederationYamlSource(
                alias       = s.alias,
                description = s.description,
                disabled    = s.disabled,
                setupSql    = s.setupSql,
                secrets     = fedStore.listSecrets(s.id).map { sec =>
                  FederationYamlSecret(
                    name        = sec.name,
                    value       = sec.value.map(_ => REDACTED),
                    externalRef = sec.externalRef
                  )
                }
              )
            }
          )
          Right(Yaml.pretty(payload.asJson))
    }

  def importYaml(tenantName: String, tenantDbName: String, body: String): Out[FederationImportSummary] =
    IO.blocking {
      resolveTenantDbId(tenantName, tenantDbName) match
        case Left(e) => Left(e)
        case Right(tenantDbId) =>
          parser.parse(body).flatMap(_.as[FederationYamlPayload]) match
            case Left(e) =>
              Left(StatusCode.BadRequest -> ErrorResponse("invalid-yaml", e.getMessage))
            case Right(payload) =>
              // 1. Reject duplicate alias inside payload
              val dupAlias = payload.federatedSources.groupBy(_.alias).find(_._2.size > 1).map(_._1)
              dupAlias match
                case Some(d) =>
                  Left(StatusCode.BadRequest -> ErrorResponse(
                    "invalid-yaml", s"duplicate alias '$d' in payload"
                  ))
                case None =>
                  // 2. Load existing for value-reuse
                  val existing: Map[(String, String), FederatedSecret] =
                    fedStore.listSources(tenantDbId).flatMap { src =>
                      fedStore.listSecrets(src.id).map(sec => (src.alias, sec.name) -> sec)
                    }.toMap

                  // 3. Validate each secret has a value (after reuse) or fail
                  val resolved = payload.federatedSources.map { src =>
                    val resolvedSecrets = src.secrets.map { sec =>
                      (sec.value, sec.externalRef) match
                        case (Some(v), None) if v == REDACTED =>
                          // Try reuse from existing
                          existing.get((src.alias, sec.name)) match
                            case Some(old) =>
                              Right(sec.copy(value = old.value, externalRef = old.externalRef))
                            case None =>
                              Left(s"secret '${sec.name}' for source '${src.alias}' has no existing value to reuse; provide value or externalRef")
                        case (None, None) =>
                          existing.get((src.alias, sec.name)) match
                            case Some(old) =>
                              Right(sec.copy(value = old.value, externalRef = old.externalRef))
                            case None =>
                              Left(s"secret '${sec.name}' for source '${src.alias}' has no existing value to reuse; provide value or externalRef")
                        case _ => Right(sec)
                    }
                    if resolvedSecrets.exists(_.isLeft) then
                      Left(resolvedSecrets.collect { case Left(e) => e }.mkString("; "))
                    else
                      Right(src.copy(secrets = resolvedSecrets.collect { case Right(s) => s }))
                  }

                  resolved.find(_.isLeft) match
                    case Some(Left(err)) =>
                      Left(StatusCode.BadRequest -> ErrorResponse("invalid-yaml", err))
                    case _ =>
                      val allSources = resolved.collect { case Right(s) => s }

                      // 4. Delete sources not in payload, then upsert
                      val incomingAliases = allSources.map(_.alias).toSet
                      fedStore.listSources(tenantDbId)
                        .filterNot(s => incomingAliases.contains(s.alias))
                        .foreach(s => fedStore.deleteSource(s.id))

                      var srcCount, secCount = 0
                      allSources.foreach { src =>
                        val srcId = existing.collectFirst {
                          case ((alias, _), sec) if alias == src.alias => sec.federatedSourceId
                        }.getOrElse(s"fs-${UUID.randomUUID().toString.take(8)}")
                        fedStore.upsertSource(FederatedSource(
                          id          = srcId,
                          tenantDbId  = tenantDbId,
                          alias       = src.alias,
                          setupSql    = src.setupSql,
                          description = src.description,
                          disabled    = src.disabled
                        ))
                        srcCount += 1

                        // Delete secrets not in payload, then upsert
                        val incomingSecretNames = src.secrets.map(_.name).toSet
                        fedStore.listSecrets(srcId)
                          .filterNot(s => incomingSecretNames.contains(s.name))
                          .foreach(s => fedStore.deleteSecret(srcId, s.name))

                        src.secrets.foreach { sec =>
                          val secId = existing.get((src.alias, sec.name))
                            .map(_.id)
                            .getOrElse(s"fsec-${UUID.randomUUID().toString.take(8)}")
                          fedStore.upsertSecret(FederatedSecret(
                            id                = secId,
                            federatedSourceId = srcId,
                            name              = sec.name,
                            value             = sec.value,
                            externalRef       = sec.externalRef
                          ))
                          secCount += 1
                        }
                      }

                      Right(FederationImportSummary(sources = srcCount, secrets = secCount))
    }