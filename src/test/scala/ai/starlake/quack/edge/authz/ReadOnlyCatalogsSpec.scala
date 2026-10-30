package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{FederatedSource, PoolKey, Role, RunningNode}
import io.circe.Decoder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

/** Each pool's read-only federated catalogs (decision 24) and the handles that carry them: the node
  * refuses every principal's write into one, so the set a handle carries must be the pool's, folded
  * as the node compares it.
  */
class ReadOnlyCatalogsSpec extends AnyFlatSpec with Matchers:

  private val Pool  = PoolKey("acme", "acme_db", "bi")
  private val Other = PoolKey("acme", "other_db", "bi")
  private val Node  =
    RunningNode(
      "quack-acme-db-bi-1",
      Pool,
      Role.Dual,
      "127.0.0.1",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH
    )
  private val Alice = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
  private val Root  = NodePrincipal("root", "root", None, superuser = true, system = false)

  private def source(alias: String, readOnly: Boolean, disabled: Boolean = false) =
    FederatedSource(
      id = s"fs-$alias",
      tenantDbId = "td-1",
      alias = alias,
      readOnly = readOnly,
      disabled = disabled
    )

  /** Runs `body` with the JVM default locale forced to Turkish, restoring the previous default on
    * every path.
    */
  private def withTurkishLocale[A](body: => A): A =
    val previous = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try body
    finally Locale.setDefault(previous)

  private def readOnlyOf(
      keys: NodeKeys,
      node: RunningNode,
      p: NodePrincipal
  ): Decoder.Result[List[String]] =
    HandleInspector.payload(keys.handle(node, p)).hcursor.get[List[String]]("readOnly")

  "FederatedReadOnlyCatalogs" should "mint no user handle before its sources are wired" in {
    val catalogs = new FederatedReadOnlyCatalogs()
    intercept[IllegalStateException](catalogs.of(Pool)).getMessage should include("not loaded yet")
    val keys = new NodeKeys("s" * 32, "r", readOnlyCatalogs = catalogs)
    intercept[IllegalStateException](keys.handle(Node, Alice))
    intercept[IllegalStateException](keys.handle(Node, Root))
    // The manager's own statements (health probes, maintenance) carry none, so they need no set.
    readOnlyOf(keys, Node, NodePrincipal.System) shouldBe Right(Nil)
  }

  it should "carry the pool's read-only aliases, disabled ones included, folded with Locale.ROOT" in
    withTurkishLocale {
      withClue(
        "the forced default locale must fold the ASCII I otherwise, or this proves nothing: "
      ) {
        "I".toLowerCase should not be "i"
      }
      val catalogs = new FederatedReadOnlyCatalogs()
      catalogs.wire(k =>
        if k == Pool then
          List(
            source("CRM_I", readOnly = true),
            source("Legacy", readOnly = true, disabled = true),
            source("writable", readOnly = false)
          )
        else Nil
      )
      catalogs.of(Pool) shouldBe Set("crm_i", "legacy")
      catalogs.of(Other) shouldBe empty
    }

  it should "keep a pool's set for its TTL, then read the sources again" in {
    val now      = new AtomicLong(0L)
    val reads    = new AtomicInteger(0)
    val sources  = new AtomicReference(List(source("crm", readOnly = true)))
    val catalogs = new FederatedReadOnlyCatalogs(() => now.get(), ttlMs = 60_000L)
    catalogs.wire { _ =>
      reads.incrementAndGet()
      sources.get()
    }
    catalogs.of(Pool) shouldBe Set("crm")
    sources.set(Nil)
    now.set(59_999L)
    catalogs.of(Pool) shouldBe Set("crm")
    reads.get shouldBe 1
    now.set(60_000L)
    catalogs.of(Pool) shouldBe empty
    reads.get shouldBe 2
  }

  it should "fail its caller when the sources cannot be read" in {
    val catalogs = new FederatedReadOnlyCatalogs()
    catalogs.wire(_ => throw new IllegalStateException("control plane down"))
    intercept[IllegalStateException](catalogs.of(Pool)).getMessage should include(
      "control plane down"
    )
  }

  // A tenant-db miss must never be cached as "no read-only catalog": the node alone enforces the
  // flag, so that answer would let writes into a read-only source through for the TTL.
  "FederatedReadOnlyCatalogs.sourcesOf" should "fail a tenant-db miss uncached, the next lookup reading again" in {
    val tenantDbId = new AtomicReference[Option[String]](None)
    val catalogs   = new FederatedReadOnlyCatalogs()
    catalogs.wire(
      FederatedReadOnlyCatalogs.sourcesOf(
        _ => tenantDbId.get(),
        Some(id => if id == "td-1" then List(source("erp_pg", readOnly = true)) else Nil)
      )
    )
    val miss = intercept[NoSuchElementException](catalogs.of(Pool))
    miss.getMessage should include("acme/acme_db")
    tenantDbId.set(Some("td-1"))
    catalogs.of(Pool) shouldBe Set("erp_pg")
  }

  it should "answer no source without a federation store, never looking the tenant-db up" in {
    val catalogs = new FederatedReadOnlyCatalogs()
    catalogs.wire(FederatedReadOnlyCatalogs.sourcesOf(_ => fail("looked up"), None))
    catalogs.of(Pool) shouldBe Set.empty
  }

  "NodeKeys" should "carry the pool's read-only catalogs, sorted, in a user's and a superuser's handle" in {
    val keys = new NodeKeys(
      "s" * 32,
      "r",
      readOnlyCatalogs = k => if k == Pool then Set("erp_pg", "crm") else Set.empty
    )
    readOnlyOf(keys, Node, Alice) shouldBe Right(List("crm", "erp_pg"))
    readOnlyOf(keys, Node, Root) shouldBe Right(List("crm", "erp_pg"))
    readOnlyOf(keys, Node.copy(poolKey = Other), Alice) shouldBe Right(Nil)
  }

  it should "carry none in the system principal's handle" in {
    val keys = new NodeKeys("s" * 32, "r", readOnlyCatalogs = _ => Set("crm"))
    readOnlyOf(keys, Node, NodePrincipal.System) shouldBe Right(Nil)
  }

  it should "carry the Locale.ROOT fold of a source's alias on a Turkish default locale" in
    withTurkishLocale {
      val catalogs = new FederatedReadOnlyCatalogs()
      catalogs.wire(_ => List(source("ERP_I", readOnly = true)))
      readOnlyOf(new NodeKeys("s" * 32, "r", readOnlyCatalogs = catalogs), Node, Alice) shouldBe
        Right(List("erp_i"))
    }
