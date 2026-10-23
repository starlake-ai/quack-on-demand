package ai.starlake.quack.edge.authz

import ai.starlake.quack.ondemand.rbac.EffectiveSet

/** Who a statement on a node runs for, as the manager vouches for it in a session handle
  * ([[NodeHandles]]). `tenant` None for a principal of no tenant.
  */
final case class NodePrincipal(
    id: String,
    name: String,
    tenant: Option[String],
    superuser: Boolean,
    system: Boolean
)

object NodePrincipal:

  /** The manager's own statements: health probes, engine stats, attach checks, Iceberg metadata,
    * maintenance, merges, the front door's admin results, RoutedExecutor's system caller.
    */
  val System: NodePrincipal =
    NodePrincipal("system", "system", None, superuser = false, system = true)

  /** No principal is bound to the statement (no handshake state). [[NodeHandles.mint]] refuses it,
    * so such a statement never reaches a node.
    */
  val Unbound: NodePrincipal =
    NodePrincipal("", "", None, superuser = false, system = false)

  /** The principal of a statement run under `effectiveSet`: its user (a superuser when the user has
    * no tenant), RoutedExecutor's system caller, or [[Unbound]].
    */
  def of(effectiveSet: Option[EffectiveSet]): NodePrincipal = effectiveSet match
    case Some(e) if e.system => System
    case Some(e)             =>
      NodePrincipal(
        e.user.id,
        e.user.username,
        e.user.tenant,
        superuser = e.user.tenant.isEmpty,
        system = false
      )
    case None => Unbound
