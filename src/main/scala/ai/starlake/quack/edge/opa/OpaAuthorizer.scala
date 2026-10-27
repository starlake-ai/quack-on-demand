package ai.starlake.quack.edge.opa

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.rbac.{EffectiveSet, HandshakeDenial, OpaPoolAccess}
import ai.starlake.quack.ondemand.state.RbacUser
import ai.starlake.quack.observability.metrics.OpaInstruments
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Composes input building, the decision cache, the HTTP client and metrics. One instance per
  * manager; tenant settings are read from the `Tenant` passed on every call, never cached here.
  */
final class OpaAuthorizer(
    cfg: OpaConfig,
    client: OpaClient,
    cache: OpaDecisionCache,
    metrics: OpaInstruments
) extends LazyLogging,
      OpaPoolAccess:

  override def isOpa(tenant: Tenant): Boolean = tenant.acl.isOpa(cfg.defaultMode)

  /** Handshake gate 4 for an opa tenant: a Deny is a permission refusal, an Error (OPA unreachable,
    * malformed answer, no URL) is `Unavailable` so the edges answer a retryable UNAVAILABLE. The
    * Error's cause is logged by `run`; the client gets [[OpaAuthorizer.UnavailableMessage]] only.
    */
  override def connect(
      tenant: Tenant,
      key: PoolKey,
      parentPools: List[String],
      user: RbacUser,
      eff: EffectiveSet,
      edge: String
  ): Either[HandshakeDenial, Unit] =
    val target = OpaTarget(key.tenant, key.tenantDb, key.pool, parentPools)
    val u      = OpaUser(user.username, eff.roles.map(_.name), eff.groups.map(_.name), eff.claims)
    connect(tenant, target, u, user.id, edge) match
      case Decision.Allow(_)           => Right(())
      case Decision.Deny(reason, _, _) =>
        Left(HandshakeDenial.Denied(s"pool access denied by policy: $reason"))
      case Decision.Error(_) => Left(HandshakeDenial.Unavailable(OpaAuthorizer.UnavailableMessage))

  def connect(
      tenant: Tenant,
      target: OpaTarget,
      user: OpaUser,
      userId: String,
      edge: String
  ): Decision =
    run(
      tenant,
      "connect",
      OpaInput.connect(target, user, edge),
      Set.empty,
      target,
      user,
      userId,
      edge,
      statementClass = "",
      text = None
    )

  def statement(
      tenant: Tenant,
      target: OpaTarget,
      user: OpaUser,
      userId: String,
      edge: String,
      statementClass: String,
      accesses: Set[TableAccess],
      text: String
  ): Decision =
    val input = OpaInput.statement(
      target,
      user,
      edge,
      statementClass,
      accesses,
      Option.when(tenant.acl.sendStatementText)(text)
    )
    run(
      tenant,
      "statement",
      input,
      accesses,
      target,
      user,
      userId,
      edge,
      statementClass,
      Some(text)
    )

  /** A node decision (in-engine authorization): the `statement` rule over what DuckDB resolved. The
    * cache key covers the whole input, so two plans that differ only in the columns, a secure view
    * or a location never share an answer.
    */
  def decide(
      tenant: Tenant,
      target: OpaTarget,
      user: OpaUser,
      userId: String,
      statementClass: String,
      accesses: List[OpaAccess],
      locations: List[OpaLocation]
  ): Decision =
    val input  = OpaInput.decide(target, user, statementClass, accesses, locations)
    val tables =
      accesses.map(a => TableAccess(TableRef(a.catalog, a.schema, a.table), a.verb)).toSet
    run(
      tenant,
      "statement",
      input,
      tables,
      target,
      user,
      userId,
      OpaInput.NodeEdge,
      statementClass,
      text = None,
      detail = sha256Hex(input.noSpaces)
    )

  /** Admin dry-run: same transport and parsing, never cached, never counted. */
  def dryRun(tenant: Tenant, input: Json, rule: String, accesses: Set[TableAccess]): Decision =
    tenant.acl.effectiveUrl(cfg.url) match
      case None      => Decision.Error(s"no OPA URL configured for tenant '${tenant.id}'")
      case Some(url) =>
        client.query(
          OpaClient.endpoint(url, tenant.acl.effectivePolicyPath, rule),
          tenant.acl.opaToken,
          input,
          accesses
        )

  private def sha256Hex(s: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xff}%02x").mkString

  private def fingerprint(tenant: Tenant, url: String): String =
    val tokenFp = tenant.acl.opaToken.map(t => sha256Hex(t).take(16)).getOrElse("")
    s"$url|${tenant.acl.effectivePolicyPath}|$tokenFp|${tenant.acl.sendStatementText}"

  private def run(
      tenant: Tenant,
      kind: String,
      input: Json,
      accesses: Set[TableAccess],
      target: OpaTarget,
      user: OpaUser,
      userId: String,
      edge: String,
      statementClass: String,
      text: Option[String],
      detail: String = ""
  ): Decision =
    tenant.acl.effectiveUrl(cfg.url) match
      case None =>
        metrics.count(tenant.id, kind, "error")
        Decision.Error(s"no OPA URL configured for tenant '${tenant.id}'")
      case Some(url) =>
        val key = OpaCacheKey(
          tenantId = tenant.id,
          settings = fingerprint(tenant, url),
          userId = userId,
          userName = user.name,
          roles = user.roles.sorted,
          groups = user.groups.sorted,
          claims = user.claims.toList.sorted,
          pool = s"${target.tenant}/${target.tenantDb}/${target.pool}",
          kind = kind,
          statementClass = statementClass,
          edge = edge,
          parentPools = target.parentPools.sorted,
          accesses = OpaInput
            .sorted(accesses)
            .map(a => s"${a.table.canonical}:${OpaInput.verbName(a.verb)}"),
          textHash = if tenant.acl.sendStatementText then sha256Hex(text.getOrElse("")) else "",
          detail = detail
        )
        val started         = System.nanoTime()
        val (decision, hit) = cache.getOrCompute(key) {
          client.query(
            OpaClient.endpoint(url, tenant.acl.effectivePolicyPath, kind),
            tenant.acl.opaToken,
            input,
            accesses
          )
        }
        val outcome =
          if hit then "cache"
          else
            decision match
              case _: Decision.Allow => "allow"
              case _: Decision.Deny  => "deny"
              case _: Decision.Error => "error"
        metrics.record(tenant.id, kind, outcome, System.nanoTime() - started)
        decision match
          case Decision.Error(cause) =>
            logger.warn(s"OPA $kind error for tenant=${tenant.id}: $cause")
          case d => logger.debug(s"OPA $kind tenant=${tenant.id} user=${user.name} -> $d")
        decision

object OpaAuthorizer:

  /** What a client sees when OPA cannot decide (unreachable, timed out, a non-2xx status, a body
    * over 1 MiB, no URL configured). The tenant admin chooses the OPA URL, so the transport detail
    * (exception class, host, port, status) would turn every outage into a network probe of whatever
    * the manager can reach; that detail goes to the manager log only.
    */
  val UnavailableMessage: String = "OPA unreachable or returned an error (see the manager log)"
