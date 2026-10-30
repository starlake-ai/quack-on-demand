package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, RunningNode}

import java.security.SecureRandom
import java.util.Base64

/** The manager's node-key secret and what derives from it: every node's key, delivered to the node
  * as its `QOD_NODE_AUTHZ_KEY` file, and the session handles the manager mints for statements on
  * that node. `replica` is the URL nodes call back for verdicts and credentials
  * (`QOD_INTERNAL_URL`); `versions` the tenants' policy versions, required: a default would mint
  * one version forever, and no change but the session context's would flush a node's cached
  * verdicts (Phase C review C-m1); `creds` each user's S3 credentials, digested into every handle
  * (this and the next three default to what fails closed); `issued` the registry of minted nonces
  * the credentials endpoint answers (None: nothing is recorded, so every credentials fetch is
  * refused as expired); `minted`, the registry of the contexts handles were minted with (None:
  * nothing is registered, so every decide call for a user statement is refused as expired);
  * `paths`, each pool's session default path (`FlightSqlRouter.usePath`, empty when the router
  * sends no USE prelude), carried in every handle so the node judges secure views against it. The
  * default, empty for every pool, makes the node hold every connection to the session default, so a
  * secure view read after a USE prelude is refused, never admitted. `readOnlyCatalogs`, each pool's
  * read-only federated catalogs (decision 24), carried in every handle but the system principal's:
  * the node refuses any principal's write into them. It has no default, since none is safe: an
  * empty set would let every principal, superusers included, write into a read-only catalog.
  */
final class NodeKeys(
    secret: String,
    val replica: String,
    clock: () => Long = () => System.currentTimeMillis(),
    versions: PolicyVersions,
    creds: UserS3Credentials = UserS3Credentials.none,
    issued: Option[IssuedHandles] = None,
    minted: Option[MintedHandles] = None,
    paths: PoolKey => String = _ => "",
    readOnlyCatalogs: ReadOnlyCatalogs
):
  require(secret.nonEmpty, "the node-key secret is empty")

  /** The key of node `nodeId`, as its secret file holds it. */
  def keyHex(nodeId: String): String = NodeHandles.nodeKeyHex(secret, nodeId)

  /** The key of node `nodeId`: what its calls to this replica (decide, credentials) and the answers
    * to them are MACed with.
    */
  def key(nodeId: String): Array[Byte] = NodeHandles.nodeKey(secret, nodeId)

  /** A fresh handle for one statement of `principal` on `node`: the node's tenant's current policy
    * version, the digest of the principal's session context and of its S3 credentials, and the
    * pool's read-only catalogs (none for the system principal: the manager's own statements).
    */
  def handle(node: RunningNode, principal: NodePrincipal): String = mint(node, principal).text

  /** [[handle]] with its nonce and expiry: what the OPA dry run collects to find what the decision
    * endpoint recorded for its statement (decision 25).
    */
  def mint(node: RunningNode, principal: NodePrincipal): MintedHandle =
    val ctx = NodeHandles.contextDigest(principal.context)
    val m   = NodeHandles.mint(
      secret,
      node.nodeId,
      replica,
      principal,
      NodeHandles.pool(node.poolKey),
      policyVersion = versions.current(node.poolKey.tenant),
      ctx = ctx,
      creds = if principal.system then "" else creds.digest(principal.id),
      path = paths(node.poolKey),
      readOnly = if principal.system then Set.empty else readOnlyCatalogs.of(node.poolKey),
      nowMs = clock()
    )
    // The credentials endpoint serves a principal's keys only for a handle this replica minted.
    if !principal.system then issued.foreach(_.put(m.nonce, node.nodeId, principal.id, m.expiresAt))
    // The decision endpoint judges this handle's statements with this context (MintedHandles),
    // checked against the digest computed here. A superuser's statements need no context.
    if !principal.superuser then
      for
        context  <- principal.context
        registry <- minted
      do registry.put(m.nonce, context, ctx, m.expiresAt)
    m

  /** [[handle]] as the prelude statement the node runs before the statement itself. */
  def prelude(node: RunningNode, principal: NodePrincipal): String =
    NodeHandles.prelude(handle(node, principal))

object NodeKeys:

  /** 32 random bytes, base64url: a node-key secret. */
  def randomSecret(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** Keys from a random secret, for components a test builds without a control plane; never in
    * production (NodeKeysRequiredSpec): a node started with other keys refuses every handle they
    * mint.
    */
  def ephemeral(): NodeKeys =
    new NodeKeys(
      randomSecret(),
      "http://127.0.0.1:0",
      versions = PolicyVersions.zero,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
