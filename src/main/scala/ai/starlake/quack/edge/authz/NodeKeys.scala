package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.RunningNode

import java.security.SecureRandom
import java.util.Base64

/** The manager's node-key secret and what derives from it: every node's key, delivered to the node
  * as its `QOD_NODE_AUTHZ_KEY` file, and the session handles the manager mints for statements on
  * that node. `replica` is the URL nodes call back for verdicts (`QOD_INTERNAL_URL`).
  */
final class NodeKeys(
    secret: String,
    val replica: String,
    clock: () => Long = () => System.currentTimeMillis()
):
  require(secret.nonEmpty, "the node-key secret is empty")

  /** The key of node `nodeId`, as its secret file holds it. */
  def keyHex(nodeId: String): String = NodeHandles.nodeKeyHex(secret, nodeId)

  /** A fresh handle for one statement of `principal` on `node`. Every handle carries policy version
    * 0: no node caches verdicts by it yet.
    */
  def handle(node: RunningNode, principal: NodePrincipal): String =
    NodeHandles.mint(
      secret,
      node.nodeId,
      replica,
      principal,
      NodeHandles.pool(node.poolKey),
      policyVersion = 0L,
      nowMs = clock()
    )

  /** [[handle]] as the prelude statement the node runs before the statement itself. */
  def prelude(node: RunningNode, principal: NodePrincipal): String =
    NodeHandles.prelude(handle(node, principal))

object NodeKeys:

  /** 32 random bytes, base64url: a node-key secret. */
  def randomSecret(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** Keys from a random secret, for components a test builds without a control plane. A node
    * started with other keys refuses every handle they mint.
    */
  def ephemeral(): NodeKeys = new NodeKeys(randomSecret(), "http://127.0.0.1:0")
