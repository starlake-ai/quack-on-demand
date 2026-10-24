package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import io.circe.Json

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Session handles: who a statement on a node runs for, vouched for by the manager (design:
  * docs/superpowers/specs/2026-10-23-in-engine-authorization-design.md, section 2).
  *
  * The manager sends one as [[prelude]], its own PREPARE right before every statement on a node
  * connection; qod-node verifies it and installs the principal on that connection. A handle is
  * `<base64url(payload)>.<hex HMAC-SHA256(node key, payload bytes)>`, the payload a JSON object
  * (`v`, `node`, `replica`, `principal`, `pool`, `policyVersion`, `nonce`, `exp`): the MAC covers
  * the bytes as sent, so the two sides never have to agree on a canonical form.
  * native/qod-node/src/handle.cpp reads it; a nonce makes it single-use and `exp` bounds it to
  * [[LifetimeMs]].
  *
  * Each node has its own key, derived from the manager's node-key secret and the node id
  * ([[nodeKey]]), so a handle minted for one node never verifies on another. The key reaches the
  * node as the secret file `QOD_NODE_AUTHZ_KEY` (NodeSecretFiles), never through argv or env.
  */
object NodeHandles:

  /** How long a handle is valid after minting. */
  val LifetimeMs: Long = 60_000L

  /** Part of what a node answers a statement that carries no current principal (qod-node's
    * kNoPrincipal): the health probe's canary expects it.
    */
  val NoPrincipalMarker: String = "has no verified principal"

  /** Part of qod-node's refusal of a handle whose MAC fails under the node's key
    * (native/qod-node/src/handle.cpp): the node's key derives from another node-key secret.
    */
  val KeyMismatchRefusal: String = "does not verify"

  /** Parts of qod-node's refusals of a handle its clock places out of bounds (handle.cpp): expired
    * (the node's clock ahead of the manager's by the lifetime or more) and valid too long (behind
    * it by more than the node's slack).
    */
  val ClockRefusals: List[String] = List("handle expired", "valid too long")

  /** `nodeError`, a node's answer to a session handle, as the manager may answer, fence, log or
    * record it: without the excerpt of the statement DuckDB appends to an error raised at parse or
    * bind (`LINE <n>: ...` and the caret line after it), which for a refused prelude would carry
    * the handle. qod-node drops it from its own init errors the same way (init_runner.cpp).
    */
  def refusalText(nodeError: String): String =
    StatementExcerpt.findFirstMatchIn(nodeError) match
      case Some(m) => nodeError.substring(0, m.start).stripTrailing()
      case None    => nodeError

  /** Where DuckDB's statement excerpt starts: `LINE <n>:` at the start or after a blank. */
  private val StatementExcerpt = """(?:^|\s)LINE \d+:""".r

  /** The key of node `nodeId`: HMAC-SHA256(secret, "qod-node-key/v1/" + nodeId), 32 bytes. */
  def nodeKey(secret: String, nodeId: String): Array[Byte] =
    hmac(
      secret.getBytes(StandardCharsets.UTF_8),
      s"qod-node-key/v1/$nodeId".getBytes(StandardCharsets.UTF_8)
    )

  /** [[nodeKey]] as the lowercase hex text of the node's secret file. */
  def nodeKeyHex(secret: String, nodeId: String): String = hex(nodeKey(secret, nodeId))

  private val random = new SecureRandom()

  /** A fresh 128-bit nonce, lowercase hex. */
  def newNonce(): String =
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    hex(bytes)

  /** A handle's `pool`: `tenant/tenantDb/pool`. */
  def pool(key: PoolKey): String = s"${key.tenant}/${key.tenantDb}/${key.pool}"

  /** The handle for one statement on `nodeId`, minted by `replica` (the URL nodes call back for
    * verdicts) for `principal` on `pool` at `nowMs`. Refuses [[NodePrincipal.Unbound]]: there is no
    * one to vouch for.
    */
  def mint(
      secret: String,
      nodeId: String,
      replica: String,
      principal: NodePrincipal,
      pool: String,
      policyVersion: Long,
      nowMs: Long,
      nonce: String = newNonce()
  ): String =
    require(principal.id.nonEmpty, "no principal is bound to this statement")
    val payload = Json
      .obj(
        "v"         -> Json.fromInt(1),
        "node"      -> Json.fromString(nodeId),
        "replica"   -> Json.fromString(replica),
        "principal" -> Json.obj(
          "id"        -> Json.fromString(principal.id),
          "name"      -> Json.fromString(principal.name),
          "tenant"    -> principal.tenant.fold(Json.Null)(Json.fromString),
          "superuser" -> Json.fromBoolean(principal.superuser),
          "system"    -> Json.fromBoolean(principal.system)
        ),
        "pool"          -> Json.fromString(pool),
        "policyVersion" -> Json.fromLong(policyVersion),
        "nonce"         -> Json.fromString(nonce),
        "exp"           -> Json.fromLong(nowMs + LifetimeMs)
      )
      .noSpaces
      .getBytes(StandardCharsets.UTF_8)
    Base64.getUrlEncoder.withoutPadding.encodeToString(payload) + "." +
      hex(hmac(nodeKey(secret, nodeId), payload))

  /** The prelude statement that installs `handle` on a node connection. A handle holds only
    * base64url characters, `.` and hex digits, so it needs no quoting; qod-node admits exactly this
    * spelling, qualified so that no macro a session creates can shadow qod_session.
    */
  def prelude(handle: String): String = s"CALL system.main.qod_session('$handle')"

  private def hmac(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
