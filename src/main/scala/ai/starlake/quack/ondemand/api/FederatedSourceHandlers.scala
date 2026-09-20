package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.federation.iceberg.IcebergRestConfig
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
  * @param catalogAliasOf
  *   resolves a tenantDbId to the tenant-db's own DuckDB catalog alias, or None if not found. Fed
  *   into `extraReserved` so an iceberg_rest source cannot be aliased onto the tenant-db's own
  *   attached name.
  */
final class FederatedSourceHandlers(
    fedStore: FederatedSourceOps,
    resolver: (String, String) => Option[String],
    tenantIdResolver: String => Option[String] = _ => None,
    audit: AuditRecorder = AuditRecorder.noop,
    scopeOf: String => Option[SessionScope] = _ => None,
    catalogAliasOf: String => Option[String]
):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val REDACTED = FederatedSecret.RedactedMarker

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
      setupSql = Option(s.setupSql).filter(_.nonEmpty),
      description = s.description,
      disabled = s.disabled,
      sourceType = s.sourceType.wire,
      config = s.config.flatMap(j => IcebergRestConfig.fromJson(j).toOption),
      readOnly = s.readOnly
    )

  /** Turn the request's loose wire fields into a validated [[FederatedSource]], or the 400 that
    * explains why not. Validation happens HERE rather than at node spawn because a bad federated
    * source fails the whole node's init SQL, taking the pool's other catalogs down with it.
    */
  private def toSource(
      id: String,
      tenantDbId: String,
      req: FederatedSourceCreateRequest,
      existing: Option[FederatedSource]
  ): Either[(StatusCode, ErrorResponse), FederatedSource] =
    val badRequest = (msg: String) =>
      (StatusCode.BadRequest, ErrorResponse("invalid_federated_source", msg))

    val typeOrError = req.sourceType.map(_.trim).filter(_.nonEmpty) match
      case None     => Right(FederatedSourceType.Sql)
      case Some(wr) =>
        FederatedSourceType
          .fromWire(wr)
          .toRight(
            badRequest(s"unknown sourceType '$wr' (expected one of: sql, iceberg_rest)")
          )

    // OWNER DECISION (session audit): EVERY federated alias, sql or iceberg_rest, is normalized
    // through Names.normalizeOrError at write time - lowercase, 1..63 chars, identifier pattern -
    // the same rule tenant, tenant-db and pool names already get. DuckDB treats catalog aliases
    // and secret names case-insensitively, so "Sales" and "sales" were two valid rows whose second
    // ATTACH failed at node spawn; normalizing here closes that for new rows. This CHANGES existing
    // behaviour for sql sources (a mixed-case alias is now stored lowercase, an over-length one is
    // a 400); the ACL parser already lowercases canonical refs, so grants are unaffected.
    val aliasOrError = ai.starlake.quack.model.Names
      .normalizeOrError(req.alias, "alias")
      .left
      .map(badRequest)

    typeOrError.flatMap { sourceType =>
      aliasOrError.flatMap { alias =>
        // An alias already claimed by a source of a DIFFERENT sourceType is a collision, not an
        // update: silently flipping "sql" <-> "iceberg_rest" through this upsert would replace
        // operator-written setupSql with a rendered config (or vice versa) with no chance to
        // review the change. A same-type re-POST (config/setupSql edit) still upserts in place.
        existing.filter(_.sourceType != sourceType) match
          case Some(clash) =>
            Left(
              badRequest(
                s"alias '$alias' already exists as a '${clash.sourceType.wire}' source; " +
                  s"delete it before creating a '${sourceType.wire}' source with the same alias " +
                  "(reserved)"
              )
            )
          case None =>
            val source = FederatedSource(
              id = id,
              tenantDbId = tenantDbId,
              alias = alias,
              setupSql = req.setupSql.getOrElse(""),
              description = req.description,
              disabled = req.disabled,
              sourceType = sourceType,
              config = req.config.map(_.toJson),
              // An external catalog is one QoD does not own, so a new iceberg_rest source is
              // read-only until an operator says otherwise. A sql source keeps today's behaviour,
              // where writes are governed by the ACL graph alone.
              readOnly = req.readOnly.getOrElse(sourceType == FederatedSourceType.IcebergRest)
            )

            val shapeErrors  = source.validate
            val configErrors = (sourceType, req.config) match
              case (FederatedSourceType.IcebergRest, Some(cfg)) =>
                // `validated` is the same gate the blob builder uses before render, so a config
                // the handler accepts is one the node will attach.
                // Reserve the tenant-db's own DuckDB catalog alias and every sibling federated
                // alias. Without these, `ATTACH 'x' AS "<tenant-db alias>"` reaches the node,
                // DuckDB answers "database with name ... already exists", and because the piped
                // CLI does not bail the node comes up with a half-applied federation blob and no
                // loud signal. This is the last point at which the operator can simply be told.
                //
                // Exclude the row being upserted BY ID, not by alias equality: `existing` (like
                // the createSource lookup that produced it) can be a legacy row whose STORED
                // alias differs in case from the normalized `alias` here, so `_.alias == alias`
                // would fail to exclude it and the row would reserve its own alias against
                // itself, 400ing an upsert that should succeed. Lowercased for the same reason --
                // `validate` already compares case-insensitively, but the set itself should not
                // rely on that to stay correct.
                val selfId   = existing.map(_.id)
                val reserved =
                  catalogAliasOf(tenantDbId).toSet ++
                    fedStore
                      .listSources(tenantDbId)
                      .filterNot(s => selfId.contains(s.id))
                      .map(_.alias.toLowerCase)
                      .toSet
                IcebergRestConfig.validated(cfg, alias, reserved).left.getOrElse(Nil)
              case _ => Nil

            val all = shapeErrors ++ configErrors
            if all.isEmpty then Right(source) else Left(badRequest(all.mkString("; ")))
      }
    }

  /** True when a RESOLVED session is present and it is not a superuser. Mirrors
    * [[SuperuserCheck.reject]]: static-key and open-mode callers (no resolvable scope) are admitted
    * here -- the perimeter (apiKeyGuard) is their gate.
    */
  private def superuserDenied(apiKey: Option[String]): Boolean =
    SuperuserCheck.reject(apiKey)(scopeOf).isDefined

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
      TenantScopeCheck.reject(apiKey, tenantName)(scopeOf) match
        case Some(e) => Left(e)
        case None    =>
          resolveTenantDbId(tenantName, tenantDbName) match
            case Left(e)           => Left(e)
            case Right(tenantDbId) =>
              // Upsert by NORMALIZED alias, so "Sales_Lake" and "sales_lake" resolve to the same
              // row rather than minting a second one the unique constraint (case-sensitive) admits.
              // A pre-existing row's STORED alias was never normalized (this rule is new), so the
              // normalized lookup can still miss a legacy mixed-case row; fall back to a
              // case-insensitive scan of the tenant-db's sources so that row is rewritten in place
              // under its normalized alias instead of minting a silent duplicate.
              val lookupAlias = ai.starlake.quack.model.Names
                .normalizeOrError(req.alias, "alias")
                .getOrElse(req.alias)
              val existing = fedStore
                .getSource(tenantDbId, lookupAlias)
                .orElse(
                  fedStore.listSources(tenantDbId).find(_.alias.equalsIgnoreCase(lookupAlias))
                )
              val id =
                existing.map(_.id).getOrElse(ai.starlake.quack.model.Names.newSurrogateId("fs"))
              toSource(id, tenantDbId, req, existing) match
                case Left(e)       => Left(e)
                case Right(source) =>
                  fedStore.upsertSource(source)
                  // NEVER include setupSql or config in the audit detail.
                  audit.rest(
                    apiKey,
                    "control-plane",
                    AuditActions.FederationSourceUpsert,
                    "ok",
                    tenant = tenantIdResolver(tenantName),
                    target = Some(req.alias)
                  )
                  Right(toSourceResponse(source))
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
      TenantScopeCheck.reject(apiKey, tenantName)(scopeOf) match
        case Some(e) => Left(e)
        case None    =>
          resolveTenantDbId(tenantName, tenantDbName) match
            case Left(e)           => Left(e)
            case Right(tenantDbId) =>
              fedStore.getSource(tenantDbId, alias) match
                case None =>
                  Left(
                    StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found")
                  )
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
      TenantScopeCheck.reject(apiKey, tenantName)(scopeOf) match
        case Some(e) => Left(e)
        case None    =>
          resolveTenantDbId(tenantName, tenantDbName) match
            case Left(e)           => Left(e)
            case Right(tenantDbId) =>
              fedStore.getSource(tenantDbId, alias) match
                case None =>
                  Left(
                    StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found")
                  )
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
                    // A blank inline value renders `ATTACH ''` (or `CREATE SECRET ... (VALUE '')`)
                    // into the node's piped init script; DuckDB refuses it, and because the piped
                    // CLI does not bail, the node comes up healthy with the catalog silently
                    // missing. `externalRef`-backed secrets resolve elsewhere and are unaffected.
                    case (Some(v), _) if v.trim.isEmpty =>
                      Left(
                        StatusCode.BadRequest -> ErrorResponse(
                          "invalid",
                          s"secret '${req.name}' has a blank value"
                        )
                      )
                    // An externalRef secret directs the manager to resolve a value
                    // from ITS OWN trust domain at node spawn: `env:` reads the
                    // manager process environment (System.getenv), and the KMS
                    // prefixes read the manager's ambient cloud / Vault credentials.
                    // That value is then inlined into the tenant's node setupSql,
                    // which the tenant can read back -- a privilege escalation from
                    // tenant admin to control-plane operator (e.g. exfiltrating
                    // QOD_SESSION_JWT_SECRET). Restrict externalRef authoring to
                    // superusers; tenant admins keep value-backed (inline) secrets.
                    // The bootstrap/manifest import paths bypass this handler and
                    // are separately superuser-gated.
                    case _ if req.externalRef.isDefined && superuserDenied(apiKey) =>
                      audit.rest(
                        apiKey,
                        "control-plane",
                        AuditActions.FederationSecretUpsert,
                        "denied",
                        tenant = tenantIdResolver(tenantName),
                        target = Some(s"$alias/${req.name}")
                      )
                      Left(
                        StatusCode.Forbidden -> ErrorResponse(
                          "superuser_required",
                          "authoring a federated secret with an externalRef requires a " +
                            "superuser session; tenant admins may use a value-backed secret"
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
      TenantScopeCheck.reject(apiKey, tenantName)(scopeOf) match
        case Some(e) => Left(e)
        case None    =>
          resolveTenantDbId(tenantName, tenantDbName) match
            case Left(e)           => Left(e)
            case Right(tenantDbId) =>
              fedStore.getSource(tenantDbId, alias) match
                case None =>
                  Left(
                    StatusCode.NotFound -> ErrorResponse("not_found", s"source '$alias' not found")
                  )
                case Some(s) =>
                  fedStore.getSecret(s.id, name) match
                    case None =>
                      Left(
                        StatusCode.NotFound -> ErrorResponse(
                          "not_found",
                          s"secret '$name' not found"
                        )
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
