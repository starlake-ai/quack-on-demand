package ai.starlake.quack.model

import java.time.Instant

/** What the runtime backend returns after a successful start. */
final case class RunningNode(
    nodeId: String,
    poolKey: PoolKey,
    role: Role,
    host: String,
    port: Int,
    token: String,
    pid: Option[Long],       // local mode only
    podName: Option[String], // k8s mode only
    startedAt: Instant,
    maxConcurrent: Int = 0, // 0 = unlimited; mutable via /api/node/setMaxConcurrent
    lastSeen: Option[Instant] =
      None, // refreshed by the health-probe; round-tripped to qodstate_node
    serverName: Option[String] = None // fleet mode only: qodstate_fleet_server.name
):

  /** Whether `other` is this same node process, not merely the same node id: a crash respawn, a
    * restart and a resume start a new process under the same id, and on Kubernetes keep its host,
    * port, token and pod name. The start time tells them apart; it is compared to the millisecond,
    * since a node row round-trips it through a Postgres timestamp (microseconds). What changes for
    * a live process (`lastSeen`, `maxConcurrent`, `role`) is left out.
    */
  def sameProcessAs(other: RunningNode): Boolean =
    nodeId == other.nodeId && host == other.host && port == other.port &&
      token == other.token && pid == other.pid && podName == other.podName &&
      serverName == other.serverName && startedAt.toEpochMilli == other.startedAt.toEpochMilli
