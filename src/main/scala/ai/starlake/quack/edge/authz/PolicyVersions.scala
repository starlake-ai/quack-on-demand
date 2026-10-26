package ai.starlake.quack.edge.authz

import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

/** Each tenant's policy version as this replica last read it (Liquibase 0050 keeps the truth in
  * `qodstate_tenant.policy_version`). Every session handle carries its node's tenant's version, and
  * nodes cache verdicts by it, so a grant change reaches cached verdicts at the next handle.
  */
trait PolicyVersions:
  def current(tenantId: String): Long

object PolicyVersions:

  /** Every tenant at version 0: for components a test builds without a control plane. */
  val zero: PolicyVersions = _ => 0L

/** [[PolicyVersions]] over a loader, refreshed by the supervisor after every local RBAC or ACL
  * mutation and on every restore() (which HA peers run on NOTIFY and on the snapshot refresh). A
  * version never moves backwards here: two refreshes racing must not publish an older value after a
  * newer one, since qod-node refuses a handle older than its connection's. A tenant the loader no
  * longer returns keeps its last value; an unknown tenant is at 0.
  */
final class PolicyVersionCache(load: () => Map[String, Long])
    extends PolicyVersions
    with LazyLogging:

  private val versions = TrieMap.empty[String, Long]

  def current(tenantId: String): Long = versions.getOrElse(tenantId, 0L)

  /** Reloads every tenant's version. A failure keeps the last values: the mutation that asked for
    * the refresh has committed, and a stale version only delays the flush of cached verdicts until
    * the next refresh or their 60 s expiry.
    */
  def refresh(): Unit =
    try
      load().foreach { case (tenant, v) =>
        versions.updateWith(tenant) {
          case Some(old) => Some(math.max(old, v))
          case None      => Some(v)
        }
      }
    catch
      case NonFatal(e) =>
        logger.warn(
          s"policy versions: refresh failed, keeping the last values: ${e.getClass.getSimpleName}"
        )
