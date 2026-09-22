package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{
  FederatedAlias,
  FederatedSecret,
  FederatedSource,
  FederatedSourceType,
  Names
}
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
  * @param attachStatusOf
  *   keyed on (tenantDbId, alias): the live attach state of one federated source aggregated across
  *   its pool's nodes, from the in-memory AttachStatusRegistry (Task 7) -- `"attached"`,
  *   `"unknown"`, or `"failed on N of M nodes"`. Replica-local like [[PoolHandlers]]'s
  *   `attachFailuresOf`; inert default so existing callers/tests keep compiling. Consulted ONLY for
  *   enabled `iceberg_rest` rows, which is exactly what `IcebergAttachVerifier` looks at: a `sql`
  *   row or a disabled one has no attach state at all, and reporting `"unknown"` for it forever
  *   reads as "we could not tell" rather than the truthful "does not apply". Called unguarded, same
  *   reasoning as [[PoolHandlers]]'s `attachFailuresOf`.
  */
final class FederatedSourceHandlers(
    fedStore: FederatedSourceOps,
    resolver: (String, String) => Option[String],
    tenantIdResolver: String => Option[String] = _ => None,
    audit: AuditRecorder = AuditRecorder.noop,
    scopeOf: String => Option[SessionScope] = _ => None,
    catalogAliasOf: String => Option[String],
    attachStatusOf: (String, String) => Option[String] = (_, _) => None
):

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

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
      readOnly = s.readOnly,
      attachStatus =
        if s.sourceType == FederatedSourceType.IcebergRest && !s.disabled then
          attachStatusOf(s.tenantDbId, s.alias)
        else None
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
    //
    // ONE exception, and it is about the rows that already exist rather than about the rule: a
    // STORED alias the rule rejects (`ext-s3`, created before this branch) is grandfathered for an
    // in-place edit under its own stored spelling. Without this, such a row could not be updated
    // through REST, CLI, MCP or manifest import at all, and since the alias is the catalog segment
    // of every RolePermission, delete-and-recreate also means re-granting. Refusing NEW invalid
    // aliases is the point of the rule; stranding a row its owner can neither edit nor fix is not.
    // The stored spelling wins over the request's, so a re-POST cannot rename a legacy row into
    // some other invalid spelling, and the row still cannot be created this way -- only updated.
    // A valid-but-uppercase alias is deliberately NOT grandfathered: it normalizes, so it keeps
    // being rewritten in place to lowercase, which is what `BootPreflight.checkFederatedAliases`
    // tells the operator will happen.
    val aliasOrError: Either[(StatusCode, ErrorResponse), String] =
      Names.normalizeOrError(req.alias, "alias") match
        case Right(normalized) => Right(normalized)
        case Left(rejection)   =>
          val grandfathered = existing.filter(row =>
            req.alias != null && FederatedAlias.fold(row.alias) == FederatedAlias.fold(req.alias)
          )
          grandfathered match
            case Some(row) => Right(row.alias)
            case None      => Left(badRequest(rejection))

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
                      .map(s => FederatedAlias.fold(s.alias))
                      .toSet
                IcebergRestConfig.validated(cfg, alias, reserved).left.getOrElse(Nil)
              case _ => Nil

            val all = shapeErrors ++ configErrors
            if all.nonEmpty then Left(badRequest(all.mkString("; ")))
            else
              // This endpoint is a create-request-as-upsert: every field is replaced by what the
              // request carries, so an OMITTED readOnly falls back to the type default rather
              // than to the stored value -- and for a `sql` source explicitly marked read-only
              // that default is false, i.e. a re-POST that only meant to edit `setupSql` silently
              // unlocks the catalog. The CLI and the MCP tool both omit the field unless
              // `--read-only`/`--no-read-only` was passed, so this is reachable without anyone
              // typing the word. Declarative replacement is this endpoint's contract and is not
              // being changed here, but it must not be SILENT: `ManifestImporter` WARNs on
              // exactly this class of downgrade (a sourceType flip carrying readOnly with it) and
              // this is the matching line for the REST path. Logged only on the true -> false
              // direction, and only once the upsert is actually going to be written.
              if existing.exists(_.readOnly) && !source.readOnly && req.readOnly.isEmpty then
                logger.warn(
                  s"federated source '$alias' (tenant-db $tenantDbId) was read-only and this " +
                    "upsert omitted readOnly, so it becomes WRITABLE; pass readOnly=true to " +
                    "keep it read-only"
                )
              Right(source)
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
              val lookupAlias = Names
                .normalizeOrError(req.alias, "alias")
                .getOrElse(req.alias)
              val existing = fedStore
                .getSource(tenantDbId, lookupAlias)
                .orElse(
                  fedStore.listSources(tenantDbId).find(_.alias.equalsIgnoreCase(lookupAlias))
                )
              val id =
                existing.map(_.id).getOrElse(Names.newSurrogateId("fs"))
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
                    // `source.alias`, not `req.alias`: the row is written under the alias
                    // `toSource` resolved (normalized, or a grandfathered legacy spelling), so
                    // auditing the raw request would record `Sales_Lake` for a row stored as
                    // `sales_lake` and leave the trail naming something that is not in the table.
                    target = Some(source.alias)
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
