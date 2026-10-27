package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.RunningNode

import java.security.SecureRandom
import java.util.Base64

/** The manager's node-key secret and what derives from it: every node's key, delivered to the node
  * as its `QOD_NODE_AUTHZ_KEY` file, and the session handles the manager mints for statements on
  * that node. `replica` is the URL nodes call back for verdicts and credentials
  * (`QOD_INTERNAL_URL`); `versions` the tenants' policy versions; `creds` each user's S3
  * credentials, digested into every handle; `issued` the registry of minted nonces the credentials
  * endpoint answers (None: nothing is recorded, so every credentials fetch is refused as expired);
  * `minted`, the registry of the contexts handles were minted with (None: nothing is registered, so
  * every decide call for a user statement is refused as expired).
  */
final class NodeKeys(
    secret: String,
    val replica: String,
    clock: () => Long = () => System.currentTimeMillis(),
    versions: PolicyVersions = PolicyVersions.zero,
    creds: UserS3Credentials = UserS3Credentials.none,
    issued: Option[IssuedHandles] = None,
    minted: Option[MintedHandles] = None
):
  require(secret.nonEmpty, "the node-key secret is empty")

  /** The key of node `nodeId`, as its secret file holds it. */
  def keyHex(nodeId: String): String = NodeHandles.nodeKeyHex(secret, nodeId)

  /** The key of node `nodeId`: what its calls to this replica (decide, credentials) and the answers
    * to them are MACed with.
    */
  def key(nodeId: String): Array[Byte] = NodeHandles.nodeKey(secret, nodeId)

  /** A fresh handle for one statement of `principal` on `node`: the node's tenant's current policy
    * version and the digest of the principal's session context and of its S3 credentials.
    */
  def handle(node: RunningNode, principal: NodePrincipal): String =
    val m = NodeHandles.mint(
      secret,
      node.nodeId,
      replica,
      principal,
      NodeHandles.pool(node.poolKey),
      policyVersion = versions.current(node.poolKey.tenant),
      ctx = NodeHandles.contextDigest(principal.context),
      creds = if principal.system then "" else creds.digest(principal.id),
      nowMs = clock()
    )
    // The credentials endpoint serves a principal's keys only for a handle this replica minted.
    if !principal.system then issued.foreach(_.put(m.nonce, node.nodeId, principal.id, m.expiresAt))
    // The decision endpoint judges this handle's statements with this context (MintedHandles).
    for
      context  <- principal.context
      registry <- minted
    do registry.put(m.nonce, context, m.expiresAt)
    m.text

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
