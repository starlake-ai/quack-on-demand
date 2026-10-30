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

/** What a data-plane session's grants follow ([[ai.starlake.quack.edge.SessionGrants]]): each
  * tenant's policy version, and this replica's policy generation. The generation moves on every
  * local policy change and every restore, before the versions are published, whether or not they
  * could be read: a local mutation whose version read failed still reaches this replica's sessions.
  */
trait PolicyClock extends PolicyVersions:
  def generation: Long

object PolicyClock:

  /** Every tenant at version 0, generation 0: for components a test builds without a control plane.
    */
  val zero: PolicyClock = new PolicyClock:
    def current(tenantId: String): Long = 0L
    def generation: Long                = 0L

/** [[PolicyVersions]] over a loader, refreshed by the supervisor after every local RBAC or ACL
  * mutation and on every restore() (which HA peers run on NOTIFY and on the snapshot refresh). A
  * version never moves backwards here: two refreshes racing must not publish an older value after a
  * newer one, since qod-node refuses a handle older than its connection's. A tenant the loader no
  * longer returns keeps its last value; an unknown tenant is at 0.
  *
  * Also the replica's policy [[generation]] ([[PolicyClock]]), which the supervisor moves
  * ([[advance]]) on every local policy change and every restore, before it publishes the versions:
  * the generation its effective-set cache serves a set under, and what open sessions follow when a
  * version read failed.
  */
final class PolicyVersionCache(load: () => Map[String, Long]) extends PolicyClock with LazyLogging:

  private val versions = TrieMap.empty[String, Long]
  private val gen      = new java.util.concurrent.atomic.AtomicLong(0L)

  def current(tenantId: String): Long = versions.getOrElse(tenantId, 0L)

  def generation: Long = gen.get()

  /** Moves the [[generation]]: every local policy change and every restore, before the versions
    * they read are published.
    */
  def advance(): Unit = gen.incrementAndGet(): Unit

  /** Reloads every tenant's version: [[read]] then [[publish]]. */
  def refresh(): Unit = publish(read())

  /** Every tenant's version as the store holds it now, to [[publish]] later; None when the load
    * failed (logged). restore() reads before it loads the state the versions vouch for and
    * publishes once that state is in place, so a version never vouches for state older than itself.
    */
  def read(): Option[Map[String, Long]] =
    try Some(load())
    catch
      case NonFatal(e) =>
        logger.warn(
          s"policy versions: refresh failed, keeping the last values: ${FailureText.of(e)}"
        )
        None

  /** [[read]] for a local mutation that has committed: a failure is logged at ERROR, since this
    * replica's handles then carry the versions of before the mutation until the next refresh (its
    * own open sessions still follow it, through the [[generation]] the mutation moved).
    */
  def readAfterMutation(): Option[Map[String, Long]] =
    try Some(load())
    catch
      case NonFatal(e) =>
        logger.error(
          "policy versions: reading them after a local policy change failed, keeping the last " +
            s"values until the next refresh: ${FailureText.of(e)}"
        )
        None

  /** Publishes what [[read]] returned, never moving a version backwards. None keeps the last
    * values: the mutation that asked for the refresh has committed, and a stale version only delays
    * the flush of cached verdicts until the next refresh or their 60 s expiry.
    */
  def publish(loaded: Option[Map[String, Long]]): Unit =
    loaded.foreach(_.foreach { case (tenant, v) =>
      versions.updateWith(tenant) {
        case Some(old) => Some(math.max(old, v))
        case None      => Some(v)
      }
    })
