package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import io.circe.{Decoder, HCursor, Json}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A minted handle: its text, and what the minting replica keeps about it (the nonce names it in
  * the decide context registry, `expiresAt` is its `exp`).
  */
final case class MintedHandle(text: String, nonce: String, expiresAt: Long)

/** A handle this manager minted, as [[NodeHandles.verify]] reads it back for a node's call. */
final case class VerifiedHandle(
    node: String,
    replica: String,
    pool: String,
    principal: NodePrincipal,
    policyVersion: Long,
    nonce: String,
    exp: Long,
    ctx: String,
    creds: String,
    readOnly: List[String]
)

/** Session handles: who a statement on a node runs for, vouched for by the manager (design:
  * docs/superpowers/specs/2026-10-23-in-engine-authorization-design.md, section 2).
  *
  * The manager sends one as [[prelude]], its own PREPARE right before every statement on a node
  * connection; qod-node verifies it and installs the principal on that connection. A handle is
  * `<base64url(payload)>.<hex HMAC-SHA256(node key, payload bytes)>`, the payload a JSON object
  * (`v`, `node`, `replica`, `principal`, `pool`, `policyVersion`, `ctx`, `creds`, `path`,
  * `readOnly`, `nonce`, `exp`): the MAC covers the bytes as sent, so the two sides never have to
  * agree on a canonical form. native/qod-node/src/handle.cpp reads it; a nonce makes it single-use
  * and `exp` bounds it to [[LifetimeMs]].
  *
  * Each node has its own key, derived from the manager's node-key secret and the node id
  * ([[nodeKey]]), so a handle minted for one node never verifies on another. The key reaches the
  * node as the secret file `QOD_NODE_AUTHZ_KEY` (NodeSecretFiles), never through argv or env.
  */
object NodeHandles:

  /** How long a handle is valid after minting. */
  val LifetimeMs: Long = 60_000L

  /** The handle version this manager writes and reads (qod-node's kHandleVersion). A node reads
    * exactly one version and refuses every other, the health probe's handle included: version 1 is
    * node protocol 2's (no enforcement in the engine), version 2 is protocols 3 and 4's (no
    * `readOnly`, so a superuser could write into a read-only catalog there). Writing 3 keeps such a
    * node unhealthy and never routed to on every backend (Kubernetes pods and fleet servers are not
    * checked by [[ai.starlake.quack.ondemand.runtime.QodNodeBinary]]).
    */
  val Version: Int = 3

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

  /** The handle's `ctx`: lowercase hex SHA-256 of the canonical session context
    * ([[DecideContext.json]]), or of `{}` for a principal minted without one.
    */
  def contextDigest(context: Option[DecideContext]): String =
    CanonicalJson.sha256Hex(context.fold(Json.obj())(DecideContext.json))

  /** [[contextDigest]] of no context. */
  val EmptyContextDigest: String = contextDigest(None)

  private val ContextDigestPattern = "[0-9a-f]{64}".r
  private val CredsPattern         = "([0-9a-f]{64})?".r
  private val NoncePattern         = "[0-9a-f]{32}".r

  /** A read-only catalog alias as a handle carries it, and qod-node reads it: 1 to 128 of
    * `[a-z0-9_]` (`FederatedAlias.fold` of a valid alias).
    */
  private val ReadOnlyAliasPattern = "[a-z0-9_]{1,128}".r

  /** The handle for one statement on `nodeId`, minted by `replica` (the URL nodes call back for
    * verdicts) for `principal` on `pool` at `nowMs`, carrying the tenant's `policyVersion`, the
    * session's `ctx` ([[contextDigest]]), the principal's S3 credential digest `creds`
    * (`UserS3Credentials.digestOf`, empty for none), the session default `path` the node expects
    * (`FlightSqlRouter.usePath`, the target of the pool's USE prelude; empty when the router sends
    * none) and the pool's read-only catalogs `readOnly` ([[ReadOnlyCatalogs]], decision 24: the
    * node refuses every principal's write into them), written sorted. Refuses
    * [[NodePrincipal.Unbound]]: there is no one to vouch for.
    */
  def mint(
      secret: String,
      nodeId: String,
      replica: String,
      principal: NodePrincipal,
      pool: String,
      policyVersion: Long,
      ctx: String,
      creds: String,
      path: String,
      readOnly: Set[String],
      nowMs: Long,
      nonce: String = newNonce()
  ): MintedHandle =
    require(principal.id.nonEmpty, "no principal is bound to this statement")
    require(policyVersion >= 0L, "a policy version is never negative")
    require(ContextDigestPattern.matches(ctx), "ctx must be 64 lowercase hex digits")
    require(CredsPattern.matches(creds), "creds must be empty or 64 lowercase hex digits")
    require(NoncePattern.matches(nonce), "a nonce is 32 lowercase hex digits")
    require(
      readOnly.forall(ReadOnlyAliasPattern.matches),
      "a read-only catalog alias is 1 to 128 of [a-z0-9_]"
    )
    val exp     = nowMs + LifetimeMs
    val payload = Json
      .obj(
        "v"         -> Json.fromInt(Version),
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
        "ctx"           -> Json.fromString(ctx),
        "creds"         -> Json.fromString(creds),
        "path"          -> Json.fromString(path),
        "readOnly"      -> Json.arr(readOnly.toList.sorted.map(Json.fromString)*),
        "nonce"         -> Json.fromString(nonce),
        "exp"           -> Json.fromLong(exp)
      )
      .noSpaces
      .getBytes(StandardCharsets.UTF_8)
    val text = Base64.getUrlEncoder.withoutPadding.encodeToString(payload) + "." +
      hex(hmac(nodeKey(secret, nodeId), payload))
    MintedHandle(text, nonce, exp)

  /** The prelude statement that installs `handle` on a node connection. A handle holds only
    * base64url characters, `.` and hex digits, so it needs no quoting; qod-node admits exactly this
    * spelling, qualified so that no macro a session creates can shadow qod_session.
    */
  def prelude(handle: String): String = s"CALL system.main.qod_session('$handle')"

  /** How long past its `exp` a handle still authenticates a node's call to this replica (decide,
    * credentials): a node calls with the handle its connection installed last.
    */
  val DecideGraceMs: Long = 5 * 60_000L

  private val MacText = "[0-9a-f]{64}".r

  /** `text`, a handle a node presents to this replica, read back: the MAC under `nodeKey`
    * (constant-time, over the payload bytes as sent), then `v`, the node, `exp` (with
    * [[DecideGraceMs]] of grace) and the payload's shape (`ctx` 64 hex digits, `creds` empty or 64,
    * `readOnly` sorted aliases, none twice). The nonce is not checked against use here: it is
    * single-use on the node, and each endpoint resolves it in its own registry. Left names the
    * first failed check and never echoes the handle.
    */
  def verify(
      text: String,
      nodeKey: Array[Byte],
      expectedNode: String,
      nowMs: Long
  ): Either[String, VerifiedHandle] =
    text.split("\\.", -1).toList match
      case List(body, mac) if MacText.matches(mac) =>
        val payload =
          try Some(Base64.getUrlDecoder.decode(body))
          catch case _: IllegalArgumentException => None
        payload match
          case None        => Left("malformed handle")
          case Some(bytes) =>
            val expected = hex(hmac(nodeKey, bytes)).getBytes(StandardCharsets.US_ASCII)
            if !MessageDigest.isEqual(expected, mac.getBytes(StandardCharsets.US_ASCII)) then
              Left("handle does not verify")
            else readPayload(bytes, expectedNode, nowMs)
      case _ => Left("malformed handle")

  private def readPayload(
      bytes: Array[Byte],
      expectedNode: String,
      nowMs: Long
  ): Either[String, VerifiedHandle] =
    io.circe.parser.parse(new String(bytes, StandardCharsets.UTF_8)) match
      case Left(_)     => Left("malformed handle payload")
      case Right(json) =>
        val c                                                                = json.hcursor
        def field[A: Decoder](cur: HCursor, name: String): Either[String, A] =
          cur.get[A](name).left.map(_ => "malformed handle payload")
        field[Int](c, "v").flatMap { v =>
          if v != Version then Left("unsupported handle version")
          else
            for
              node    <- field[String](c, "node")
              _       <- Either.cond(node == expectedNode, (), "handle names another node")
              exp     <- field[Long](c, "exp")
              _       <- Either.cond(nowMs <= exp + DecideGraceMs, (), "handle expired")
              replica <- field[String](c, "replica")
              pool    <- field[String](c, "pool")
              version <- field[Long](c, "policyVersion")
              nonce   <- field[String](c, "nonce")
              _       <- Either.cond(NoncePattern.matches(nonce), (), "malformed handle payload")
              ctx     <- field[String](c, "ctx")
              _ <- Either.cond(ContextDigestPattern.matches(ctx), (), "malformed handle payload")
              creds    <- field[String](c, "creds")
              _        <- Either.cond(CredsPattern.matches(creds), (), "malformed handle payload")
              readOnly <- field[List[String]](c, "readOnly")
              _        <- Either.cond(wellFormedReadOnly(readOnly), (), "malformed handle payload")
              p        <- c.downField("principal").success.toRight("malformed handle payload")
              id       <- field[String](p, "id")
              name     <- field[String](p, "name")
              tenant   <- field[Option[String]](p, "tenant")
              su       <- field[Boolean](p, "superuser")
              system   <- field[Boolean](p, "system")
            yield VerifiedHandle(
              node,
              replica,
              pool,
              NodePrincipal(id, name, tenant, superuser = su, system = system),
              version,
              nonce,
              exp,
              ctx,
              creds,
              readOnly
            )
        }

  /** `aliases` as a handle carries them: each a read-only catalog alias, sorted, none twice. */
  private def wellFormedReadOnly(aliases: List[String]): Boolean =
    aliases.forall(ReadOnlyAliasPattern.matches) && aliases == aliases.distinct.sorted

  private[authz] def hmac(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)

  private[authz] def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
