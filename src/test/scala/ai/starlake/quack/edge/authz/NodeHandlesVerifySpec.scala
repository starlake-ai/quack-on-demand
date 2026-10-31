package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64

class NodeHandlesVerifySpec extends AnyFlatSpec with Matchers:

  private val Secret = "qod-verify-spec-node-key-secret-0123456789"
  private val Pool   = PoolKey("acme", "acme_lake", "bi")
  private val Node   =
    RunningNode(
      "quack-acme-lake-bi-1",
      Pool,
      Role.Dual,
      "127.0.0.1",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH
    )
  private val Key   = NodeHandles.nodeKey(Secret, Node.nodeId)
  private val Now   = 1_761_200_000_000L
  private val Alice = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
  private val Creds = "d" * 64

  private val digests: UserS3Credentials = new UserS3Credentials:
    def of(userId: String)     = Nil
    def digest(userId: String) = if userId == "u-1" then Creds else ""

  private def mint(p: NodePrincipal = Alice): String =
    new NodeKeys(
      Secret,
      "http://10.0.0.1:20900",
      () => Now,
      creds = digests,
      readOnlyCatalogs = _ => Set("erp_pg", "crm"),
      versions = ai.starlake.quack.edge.authz.PolicyVersions.zero
    ).handle(Node, p)

  /** A payload this spec writes itself, MACed with the node key: shapes the minting code never
    * produces.
    */
  private def forged(payload: Json): String =
    val bytes = payload.noSpaces.getBytes(UTF_8)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) + "." +
      NodeHandles.hex(NodeHandles.hmac(Key, bytes))

  private val Payload = Json.obj(
    "v"         -> Json.fromInt(3),
    "node"      -> Json.fromString(Node.nodeId),
    "replica"   -> Json.fromString("http://10.0.0.1:20900"),
    "principal" -> Json.obj(
      "id"        -> Json.fromString("u-1"),
      "name"      -> Json.fromString("alice"),
      "tenant"    -> Json.fromString("acme"),
      "superuser" -> Json.False,
      "system"    -> Json.False
    ),
    "pool"          -> Json.fromString("acme/acme_lake/bi"),
    "policyVersion" -> Json.fromLong(7L),
    "ctx"           -> Json.fromString("0" * 64),
    "creds"         -> Json.fromString(""),
    "readOnly"      -> Json.arr(),
    "nonce"         -> Json.fromString("0123456789abcdef0123456789abcdef"),
    "exp"           -> Json.fromLong(Now + 60_000L)
  )

  "verify" should "return what a minted handle carries" in {
    val h = NodeHandles.verify(mint(), Key, Node.nodeId, Now).toOption.get
    h.node shouldBe Node.nodeId
    h.replica shouldBe "http://10.0.0.1:20900"
    h.pool shouldBe "acme/acme_lake/bi"
    h.principal shouldBe Alice
    h.policyVersion shouldBe 0L
    h.ctx shouldBe NodeHandles.EmptyContextDigest
    h.creds shouldBe Creds
    h.readOnly shouldBe List("crm", "erp_pg")
    h.nonce should fullyMatch regex "[0-9a-f]{32}"
    h.exp shouldBe Now + NodeHandles.LifetimeMs
  }

  it should "read the flags, a principal of no tenant, and an empty creds" in {
    val root = NodePrincipal("root", "root", None, superuser = true, system = false)
    val h    = NodeHandles.verify(mint(root), Key, Node.nodeId, Now).toOption.get
    h.principal shouldBe root
    h.creds shouldBe ""
    h.readOnly shouldBe List("crm", "erp_pg")
    NodeHandles.verify(forged(Payload), Key, Node.nodeId, Now).map(_.policyVersion) shouldBe Right(
      7L
    )
  }

  it should "refuse a handle for another node, under another key, or tampered with" in {
    NodeHandles.verify(mint(), Key, "quack-acme-lake-bi-2", Now) shouldBe
      Left("handle names another node")
    NodeHandles.verify(mint(), NodeHandles.nodeKey(Secret, "x"), Node.nodeId, Now) shouldBe
      Left("handle does not verify")
    val text     = mint()
    val tampered = text.updated(3, if text.charAt(3) == 'A' then 'B' else 'A')
    NodeHandles.verify(tampered, Key, Node.nodeId, Now).isLeft shouldBe true
  }

  it should "accept an expired handle for DecideGraceMs, then refuse it" in {
    val exp = Now + NodeHandles.LifetimeMs
    NodeHandles.verify(mint(), Key, Node.nodeId, exp + NodeHandles.DecideGraceMs).isRight shouldBe
      true
    NodeHandles.verify(mint(), Key, Node.nodeId, exp + NodeHandles.DecideGraceMs + 1) shouldBe
      Left("handle expired")
  }

  it should "refuse malformed text" in {
    val mac = "ab" * 32
    List("", "abc", "a.b.c", s"!!!.$mac", s"eyJ2IjoxfQ.${mac.toUpperCase}", s"eyJ2IjoxfQ.$mac.")
      .foreach(t => withClue(t)(NodeHandles.verify(t, Key, Node.nodeId, Now).isLeft shouldBe true))
  }

  it should "refuse a MACed payload of another version or shape" in {
    def verified(p: Json) = NodeHandles.verify(forged(p), Key, Node.nodeId, Now)
    verified(Payload.mapObject(_.add("v", Json.fromInt(4)))) shouldBe
      Left("unsupported handle version")
    // Version 2 is node protocols 3 and 4's handle (no readOnly); version 1 node protocol 2's.
    verified(Payload.mapObject(_.add("v", Json.fromInt(2)))) shouldBe
      Left("unsupported handle version")
    verified(Payload.mapObject(_.add("v", Json.fromInt(1)))) shouldBe
      Left("unsupported handle version")
    verified(Payload.mapObject(_.add("readOnly", Json.arr(Json.fromString("crm"))))).map(
      _.readOnly
    ) shouldBe Right(List("crm"))
    List(
      Payload.mapObject(_.remove("ctx")),
      Payload.mapObject(_.add("ctx", Json.fromString("XYZ"))),
      Payload.mapObject(_.remove("creds")),
      Payload.mapObject(_.add("creds", Json.fromString("D" * 64))),
      Payload.mapObject(_.add("creds", Json.fromString("d" * 63))),
      Payload.mapObject(_.remove("principal")),
      Payload.mapObject(_.remove("readOnly")),
      Payload.mapObject(_.add("readOnly", Json.fromString("crm"))),
      Payload.mapObject(_.add("readOnly", Json.arr(Json.fromInt(7)))),
      Payload.mapObject(_.add("readOnly", Json.arr(Json.fromString("CRM")))),
      Payload.mapObject(_.add("readOnly", Json.arr(Json.fromString("crm-x")))),
      Payload.mapObject(
        _.add("readOnly", Json.arr(Json.fromString("erp"), Json.fromString("crm")))
      ),
      Payload.mapObject(
        _.add("readOnly", Json.arr(Json.fromString("crm"), Json.fromString("crm")))
      ),
      Json.arr()
    ).foreach(p => withClue(p.noSpaces)(verified(p) shouldBe Left("malformed handle payload")))
  }
