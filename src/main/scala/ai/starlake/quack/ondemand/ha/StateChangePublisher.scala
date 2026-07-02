package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.ondemand.state.ControlPlaneStore

/** Broadcast points for cross-replica cache coherence. Handlers on peer replicas re-run the
  * snapshot load (topology and RBAC both live in the one snapshot, so both channels converge on the
  * same refresh; two channels are kept for observability and future narrowing).
  */
trait StateChangePublisher:
  def topologyChanged(): Unit
  def rbacChanged(): Unit

object StateChangePublisher:
  val noop: StateChangePublisher = new StateChangePublisher:
    def topologyChanged(): Unit = ()
    def rbacChanged(): Unit     = ()

final class PgStateChangePublisher(store: ControlPlaneStore) extends StateChangePublisher:
  def topologyChanged(): Unit = store.notifyListeners("qod_topology", "")
  def rbacChanged(): Unit     = store.notifyListeners("qod_rbac", "")
