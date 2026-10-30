package ai.starlake.quack.edge.authz

import ai.starlake.acl.parser.Verb
import ai.starlake.quack.model.{PoolKey, Role, RunningNode, UserS3Credential}
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{
  RbacGroup,
  RbacRole,
  RbacUser,
  RoleColumnPolicy,
  RolePermission
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*

class NodeHandlesSpec extends AnyFlatSpec with Matchers:

  private val GoldenSecret = "qod-golden-node-key-secret-0123456789"
  private val GoldenNode   = "quack-acme-db-bi-1"
  private val Alice        =
    NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
  private val Ctx0 = NodeHandles.EmptyContextDigest

  private def golden(key: String): String =
    Files
      .readAllLines(Paths.get("native/qod-node/tests/golden/handle-v3.txt"))
      .asScala
      .collectFirst { case l if l.startsWith(s"$key=") => l.drop(key.length + 1) }
      .get

  private val GoldenContext = DecideContext(
    EffectiveSet(
      RbacUser("u-1", Some("acme"), "alice", "user"),
      List(RbacRole("r-1", "acme", "analyst")),
      List(RbacGroup("g-1", "acme", "bi")),
      List(RolePermission("p-1", "r-1", "acme_db", "main", "*", "RO")),
      Nil
    ),
    PoolKey("acme", "acme_db", "bi"),
    restriction = None
  )

  "mint" should "produce exactly the golden handle qod-node's handle_test verifies" in {
    NodeHandles.nodeKeyHex(GoldenSecret, GoldenNode) shouldBe golden("key")
    val minted = NodeHandles.mint(
      GoldenSecret,
      GoldenNode,
      "http://10.0.0.1:20900",
      Alice,
      "acme/acme_db/bi",
      policyVersion = 42L,
      ctx = golden("ctx"),
      creds = golden("creds"),
      path = golden("path"),
      readOnly = Set("erp_pg", "crm"),
      nowMs = 1761200000000L,
      nonce = "0123456789abcdef0123456789abcdef"
    )
    minted.text shouldBe golden("handle")
    minted.nonce shouldBe "0123456789abcdef0123456789abcdef"
    minted.expiresAt shouldBe 1761200060000L
    golden("readOnly") shouldBe "crm,erp_pg"
  }

  it should "write handle version 3, which a node of protocol 4 refuses" in {
    // A protocol-4 node does not read readOnly, so a superuser could write into a read-only catalog
    // there; it refuses every handle whose v is not 2, the probe's included: so it stays unroutable.
    val minted =
      NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set.empty, 1000L)
    HandleInspector.payload(minted.text).hcursor.get[Int]("v") shouldBe Right(3)
  }

  it should "carry the read-only catalogs sorted, and an empty array for none" in {
    val minted =
      NodeHandles.mint(
        "s" * 32,
        "n",
        "r",
        Alice,
        "p",
        0L,
        Ctx0,
        "",
        "",
        Set("b_2", "a", "a1"),
        1000L
      )
    HandleInspector.payload(minted.text).hcursor.get[List[String]]("readOnly") shouldBe
      Right(List("a", "a1", "b_2"))
    val none = NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set.empty, 1000L)
    HandleInspector.payload(none.text).hcursor.get[List[String]]("readOnly") shouldBe Right(Nil)
    val longest =
      NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set("a" * 128), 1000L)
    HandleInspector.payload(longest.text).hcursor.get[List[String]]("readOnly") shouldBe
      Right(List("a" * 128))
  }

  it should "refuse a read-only alias that is not 1 to 128 of [a-z0-9_], as qod-node does" in
    List("", "CRM", "crm-x", "crım", "a" * 129).foreach { bad =>
      withClue(s"alias '$bad': ") {
        intercept[IllegalArgumentException](
          NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set(bad), 1000L)
        ).getMessage should include("read-only catalog alias")
      }
    }

  it should "expire the handle LifetimeMs after minting and use a fresh nonce each time" in {
    val a  = NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set.empty, 1000L)
    val b  = NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set.empty, 1000L)
    val pa = HandleInspector.payload(a.text)
    pa.hcursor.get[Long]("exp") shouldBe Right(1000L + NodeHandles.LifetimeMs)
    pa.hcursor.get[String]("nonce") shouldBe Right(a.nonce)
    a.expiresAt shouldBe 1000L + NodeHandles.LifetimeMs
    a.nonce should not be b.nonce
    a.nonce should fullyMatch regex "[0-9a-f]{32}"
  }

  it should "carry the session default path, empty for none" in {
    val withPath =
      NodeHandles.mint(
        "s" * 32,
        "n",
        "r",
        Alice,
        "p",
        0L,
        Ctx0,
        "",
        "acme_db.tpch1",
        Set.empty,
        1000L
      )
    HandleInspector.payload(withPath.text).hcursor.get[String]("path") shouldBe Right(
      "acme_db.tpch1"
    )
    val none = NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, "", "", Set.empty, 1000L)
    HandleInspector.payload(none.text).hcursor.get[String]("path") shouldBe Right("")
  }

  "NodeKeys" should "stamp each pool's USE prelude target into its handles" in {
    val pool = PoolKey("acme", "acme_db", "bi")
    val node = RunningNode("n-1", pool, Role.Dual, "127.0.0.1", 1, "t", None, None, Instant.EPOCH)
    val keys = new NodeKeys(
      "s" * 32,
      "r",
      paths = k => if k == pool then "acme_db.tpch1" else "",
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val other = node.copy(poolKey = PoolKey("acme", "other_db", "bi"))
    HandleInspector.payload(keys.handle(node, Alice)).hcursor.get[String]("path") shouldBe
      Right("acme_db.tpch1")
    HandleInspector.payload(keys.handle(other, Alice)).hcursor.get[String]("path") shouldBe Right(
      ""
    )
  }

  "mint" should "refuse to vouch for no principal" in {
    val e = intercept[IllegalArgumentException](
      NodeHandles.mint(
        "s" * 32,
        "n",
        "r",
        NodePrincipal.Unbound,
        "p",
        0L,
        Ctx0,
        "",
        "",
        Set.empty,
        1000L
      )
    )
    e.getMessage should include("no principal is bound to this statement")
  }

  it should "refuse a ctx that is not 64 lowercase hex digits, and a negative policy version" in {
    List("", "a" * 63, "A" * 64, "g" * 64, Ctx0 + "0").foreach { bad =>
      withClue(s"ctx '$bad': ") {
        intercept[IllegalArgumentException](
          NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, bad, "", "", Set.empty, 1000L)
        ).getMessage should include("ctx")
      }
    }
    intercept[IllegalArgumentException](
      NodeHandles.mint("s" * 32, "n", "r", Alice, "p", -1L, Ctx0, "", "", Set.empty, 1000L)
    ).getMessage should include("policy version")
  }

  it should "refuse a nonce that is not 32 lowercase hex digits, as qod-node does" in
    List("", "a" * 31, "a" * 33, "A" * 32, "g" * 32).foreach { bad =>
      withClue(s"nonce '$bad': ") {
        intercept[IllegalArgumentException](
          NodeHandles.mint(
            "s" * 32,
            "n",
            "r",
            Alice,
            "p",
            0L,
            Ctx0,
            "",
            "",
            Set.empty,
            1000L,
            nonce = bad
          )
        ).getMessage should include("nonce")
      }
    }

  it should "refuse a creds that is neither empty nor 64 lowercase hex digits" in
    List("a" * 63, "A" * 64, "g" * 64, "a" * 65).foreach { bad =>
      withClue(s"creds '$bad': ") {
        intercept[IllegalArgumentException](
          NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, Ctx0, bad, "", Set.empty, 1000L)
        ).getMessage should include("creds")
      }
    }

  "the credentials digest" should "be the golden one, over the golden credential set" in {
    val c = UserS3Credential(
      "0123456789abcdef0123456789abcdef",
      "u-1",
      "s3://acme-etl/",
      "AKIAEXAMPLE",
      "s",
      None,
      None,
      None,
      None,
      1761000000000L,
      1761100000000L
    )
    CanonicalJson.render(UserS3Credentials.digestJson(List(c))) shouldBe golden("credentials")
    UserS3Credentials.digestOf(List(c)) shouldBe golden("creds")
  }

  "a node key" should "differ per node and per secret" in {
    NodeHandles.nodeKeyHex("a" * 32, "n1") should not be NodeHandles.nodeKeyHex("a" * 32, "n2")
    NodeHandles.nodeKeyHex("a" * 32, "n1") should not be NodeHandles.nodeKeyHex("b" * 32, "n1")
    NodeHandles.nodeKeyHex("a" * 32, "n1") should fullyMatch regex "[0-9a-f]{64}"
  }

  "prelude" should "be the exact statement qod-node's parse screen admits" in {
    NodeHandles.prelude("abc.0f") shouldBe "CALL system.main.qod_session('abc.0f')"
    // The golden handle's prelude, which handle_test reads the handle back out of
    // (qod::PreludeHandle): a spelling change on either side breaks one of the two.
    NodeHandles.prelude(golden("handle")) shouldBe golden("prelude")
  }

  "refusalText" should "drop DuckDB's excerpt of the statement, which would carry the handle" in {
    val refusal = "Permission Error: qod-node: session handle refused: handle MAC does not verify"
    val excerpt = "\n\nLINE 1: CALL system.main.qod_session('eyJ2IjoxfQ.0123abcd')\n" +
      "                                         ^"
    NodeHandles.refusalText(refusal + excerpt) shouldBe refusal
    NodeHandles.refusalText(s"$refusal\n\nLINE 12: SELECT 1") shouldBe refusal
    // Flattened onto one line, it still goes.
    NodeHandles.refusalText(s"$refusal LINE 3: CALL x('y')") shouldBe refusal
  }

  it should "keep a text with no excerpt as it is" in {
    val refusal = "qod-node: session handle refused: handle expired"
    NodeHandles.refusalText(refusal) shouldBe refusal
    NodeHandles.refusalText("Binder Error: no PIPELINE 2: here") shouldBe
      "Binder Error: no PIPELINE 2: here"
  }

  "the session context digest" should "be the golden one, over the golden canonical context" in {
    CanonicalJson.render(DecideContext.json(GoldenContext)) shouldBe golden("context")
    NodeHandles.contextDigest(Some(GoldenContext)) shouldBe golden("ctx")
  }

  it should "be the digest of {} for a principal minted without a context" in {
    NodeHandles.contextDigest(None) shouldBe
      "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    NodeHandles.EmptyContextDigest shouldBe NodeHandles.contextDigest(None)
  }

  it should "not depend on the order of roles, groups or permissions" in {
    val e  = GoldenContext.effective
    val e2 = e.copy(
      roles = e.roles :+ RbacRole("r-2", "acme", "ops"),
      permissions = e.permissions :+ RolePermission("p-2", "r-2", "acme_db", "main", "t", "RW")
    )
    val e3 = e2.copy(roles = e2.roles.reverse, permissions = e2.permissions.reverse)
    NodeHandles.contextDigest(Some(GoldenContext.copy(effective = e2))) shouldBe
      NodeHandles.contextDigest(Some(GoldenContext.copy(effective = e3)))
  }

  it should "change with everything a decision reads" in {
    val base     = NodeHandles.contextDigest(Some(GoldenContext))
    val e        = GoldenContext.effective
    val variants = List(
      "a JWT role"      -> GoldenContext.copy(effective = e.copy(roles = Nil)),
      "a JWT group"     -> GoldenContext.copy(effective = e.copy(groups = Nil)),
      "a claim"         -> GoldenContext.copy(effective = e.copy(claims = Map("dept" -> "fin"))),
      "a permission"    -> GoldenContext.copy(effective = e.copy(permissions = Nil)),
      "a column policy" -> GoldenContext.copy(effective =
        e.copy(columnPolicies =
          List(RoleColumnPolicy("cp-1", "r-1", "acme_db", "main", "t", "ssn", "deny", None))
        )
      ),
      "a verb ceiling" -> GoldenContext.copy(effective =
        e.copy(verbCeiling = Some(Set(Verb.Read)))
      ),
      "the pool"   -> GoldenContext.copy(poolKey = PoolKey("acme", "acme_db", "etl")),
      "the user"   -> GoldenContext.copy(effective = e.copy(user = e.user.copy(id = "u-2"))),
      "a PAT"      -> GoldenContext.copy(restriction = Some(TokenRestriction.Unrestricted)),
      "branchOnly" -> GoldenContext.copy(restriction =
        Some(TokenRestriction.Unrestricted.copy(branchOnly = true))
      ),
      "PAT roles" -> GoldenContext.copy(restriction =
        Some(TokenRestriction.Unrestricted.copy(roles = Some(Set("analyst"))))
      ),
      "a PAT database" -> GoldenContext.copy(restriction =
        Some(TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_db"))))
      )
    )
    variants.foreach { (what, ctx) =>
      withClue(s"$what: ")(NodeHandles.contextDigest(Some(ctx)) should not be base)
    }
  }

  it should "ignore what does not change authority: a PAT's expiry and limits" in {
    val r = TokenRestriction.Unrestricted
    NodeHandles.contextDigest(Some(GoldenContext.copy(restriction = Some(r)))) shouldBe
      NodeHandles.contextDigest(
        Some(
          GoldenContext.copy(restriction =
            Some(
              r.copy(
                expiresAt = Some(Instant.ofEpochSecond(5)),
                stmtTimeoutMs = Some(10),
                maxRows = Some(3)
              )
            )
          )
        )
      )
  }

  private val Node2 = RunningNode(
    "quack-acme-db-bi-2",
    PoolKey("acme", "acme_db", "bi"),
    Role.Dual,
    "127.0.0.1",
    21900,
    "tok",
    None,
    None,
    Instant.EPOCH
  )

  "NodeKeys" should "mint for the node's own id and pool, at its clock" in {
    val keys = new NodeKeys(
      "k" * 32,
      "http://127.0.0.1:20900",
      () => 5000L,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val p = HandleInspector.payload(keys.prelude(Node2, Alice))
    p.hcursor.get[String]("node") shouldBe Right("quack-acme-db-bi-2")
    p.hcursor.get[String]("pool") shouldBe Right("acme/acme_db/bi")
    p.hcursor.get[String]("replica") shouldBe Right("http://127.0.0.1:20900")
    p.hcursor.get[Long]("exp") shouldBe Right(5000L + NodeHandles.LifetimeMs)
    p.hcursor.downField("principal").get[String]("id") shouldBe Right("u-1")
    keys.keyHex(Node2.nodeId) shouldBe NodeHandles.nodeKeyHex("k" * 32, Node2.nodeId)
  }

  it should "carry the node's tenant's current policy version and the principal's ctx" in {
    var acme                     = 7L
    val versions: PolicyVersions = t => if t == "acme" then acme else 99L
    val keys                     = new NodeKeys(
      "k" * 32,
      "http://127.0.0.1:20900",
      () => 5000L,
      versions,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val withContext = Alice.copy(context = Some(GoldenContext))
    val p1          = HandleInspector.payload(keys.handle(Node2, withContext))
    p1.hcursor.get[Long]("policyVersion") shouldBe Right(7L)
    p1.hcursor.get[String]("ctx") shouldBe Right(golden("ctx"))
    acme = 8L
    HandleInspector.payload(keys.handle(Node2, Alice)).hcursor.get[Long]("policyVersion") shouldBe
      Right(8L)
    HandleInspector
      .payload(keys.handle(Node2, NodePrincipal.System))
      .hcursor
      .get[String]("ctx") shouldBe
      Right(NodeHandles.EmptyContextDigest)
  }

  it should "carry policy version 0 without a version source" in {
    val keys = new NodeKeys(
      "k" * 32,
      "http://127.0.0.1:20900",
      () => 5000L,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    HandleInspector.payload(keys.handle(Node2, Alice)).hcursor.get[Long]("policyVersion") shouldBe
      Right(0L)
  }

  it should "carry the principal's credential digest, none for the system, and record the nonce" in {
    val digests: UserS3Credentials = new UserS3Credentials:
      def of(userId: String)     = Nil
      def digest(userId: String) = if userId == "u-1" then "d" * 64 else ""
    val issued = new IssuedHandles(clock = () => 5000L)
    val keys   = new NodeKeys(
      "k" * 32,
      "http://127.0.0.1:20900",
      () => 5000L,
      creds = digests,
      issued = Some(issued),
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val p = HandleInspector.payload(keys.handle(Node2, Alice))
    p.hcursor.get[String]("creds") shouldBe Right("d" * 64)
    val nonce = p.hcursor.get[String]("nonce").toOption.get
    issued.get(nonce) shouldBe Some(IssuedHandles.Issued(Node2.nodeId, "u-1"))
    val s = HandleInspector.payload(keys.handle(Node2, NodePrincipal.System))
    s.hcursor.get[String]("creds") shouldBe Right("")
    issued.get(s.hcursor.get[String]("nonce").toOption.get) shouldBe None
    keys.key(Node2.nodeId).toSeq shouldBe NodeHandles.nodeKey("k" * 32, Node2.nodeId).toSeq
  }

  "NodePrincipal.of" should "name the user, flag a superuser, and map the system caller" in {
    def eff(tenant: Option[String], system: Boolean = false) =
      Some(
        EffectiveSet(RbacUser("u-9", tenant, "bob", "user"), Nil, Nil, Nil, Nil, system = system)
      )
    NodePrincipal.of(eff(Some("acme"))) shouldBe
      NodePrincipal("u-9", "bob", Some("acme"), superuser = false, system = false)
    NodePrincipal.of(eff(None)) shouldBe
      NodePrincipal("u-9", "bob", None, superuser = true, system = false)
    NodePrincipal.of(eff(None, system = true)) shouldBe NodePrincipal.System
    NodePrincipal.of(None) shouldBe NodePrincipal.Unbound
  }

  "NoPrincipalMarker" should "be part of qod-node's refusal" in {
    Files.readString(Paths.get("native/qod-node/src/authz.hpp")) should include(
      NodeHandles.NoPrincipalMarker
    )
  }

  "the refusals the health warning names a cause for" should "be qod-node's own words" in {
    val verify = Files.readString(Paths.get("native/qod-node/src/handle.cpp"))
    (NodeHandles.KeyMismatchRefusal :: NodeHandles.ClockRefusals).foreach(m =>
      verify should include(m)
    )
  }
