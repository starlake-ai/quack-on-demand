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
    * port, token and pod name. The start time tells them apart, compared exactly: every backend
    * stamps it with [[RunningNode.startStamp]], so the node row's Postgres round trip returns it
    * unchanged. What changes for a live process (`lastSeen`, `maxConcurrent`, `role`) is left out.
    */
  def sameProcessAs(other: RunningNode): Boolean =
    nodeId == other.nodeId && host == other.host && port == other.port &&
      token == other.token && pid == other.pid && podName == other.podName &&
      serverName == other.serverName && startedAt == other.startedAt

object RunningNode:

  /** A node's start time as a backend records it: truncated to the microsecond, the precision of
    * the node row's Postgres timestamp, which would otherwise round a start in the last half
    * microsecond of a millisecond into the next one on reload, and make the reloaded node compare
    * as another process ([[RunningNode.sameProcessAs]]).
    */
  def startStamp(at: Instant): Instant = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
