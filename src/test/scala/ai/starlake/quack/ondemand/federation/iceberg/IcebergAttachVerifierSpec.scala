package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType, PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class IcebergAttachVerifierSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val poolKey = PoolKey("acme", "acme_db", "bi")

  private val node = RunningNode(
    nodeId = "n-1",
    poolKey = poolKey,
    role = Role.Dual,
    host = "127.0.0.1",
    port = 21900,
    token = "t",
    pid = None,
    podName = None,
    startedAt = Instant.now()
  )

  private def iceSrc(alias: String) = FederatedSource(
    id = "fs-" + alias,
    tenantDbId = "td-1",
    alias = alias,
    sourceType = FederatedSourceType.IcebergRest,
    config = Some(
      IcebergRestConfig(
        warehouse = "w",
        authType = Some(IcebergAuthType.NoAuth),
        uri = "http://c"
      ).toJson
    )
  )

  /** Records every SQL the verifier sent to the node. */
  private class Recorder:
    val sent = new AtomicReference[List[String]](Nil)
    def run(reply: Either[String, Unit]): (RunningNode, String) => IO[Either[String, Unit]] =
      (_, sql) => IO.delay { sent.updateAndGet(sql :: _); reply }

  private def verifier(
      sources: List[FederatedSource],
      attached: Set[String],
      run: (RunningNode, String) => IO[Either[String, Unit]],
      registry: AttachStatusRegistry = new AttachStatusRegistry()
  ) = (
    new IcebergAttachVerifier(
      sourcesOf = _ => sources,
      renderOne = s => IO.pure(s"-- rendered ${s.alias}"),
      runOnNode = run,
      listCatalogs = _ => IO.pure(Right(attached)),
      registry = registry
    ),
    registry
  )

  "verify" should "do nothing and latch when every alias is attached" in {
    val rec      = new Recorder
    val (v, reg) =
      verifier(List(iceSrc("sales_lake")), Set("acme_db", "sales_lake"), rec.run(Right(())))
    v.verify(node).unsafeRunSync()
    rec.sent.get() shouldBe empty
    reg.latched("n-1") shouldBe true
    reg.failuresFor("n-1") shouldBe empty
  }

  it should "re-attach exactly the missing alias" in {
    val rec      = new Recorder
    val (v, reg) = verifier(
      List(iceSrc("sales_lake"), iceSrc("other_lake")),
      Set("acme_db", "other_lake"),
      rec.run(Right(()))
    )
    v.verify(node).unsafeRunSync()
    rec.sent.get() shouldBe List("-- rendered sales_lake")
    reg.latched("n-1") shouldBe true
  }

  it should "record the node's real error when the re-attach fails" in {
    val rec      = new Recorder
    val (v, reg) = verifier(
      List(iceSrc("sales_lake")),
      Set("acme_db"),
      rec.run(
        Left("Invalid Configuration Error: Could not get token from https://idp/oauth/tokens")
      )
    )
    v.verify(node).unsafeRunSync()
    val f = reg.failuresFor("n-1").head
    f.alias shouldBe "sales_lake"
    f.error should include("Could not get token")
    f.attempts shouldBe 1
    reg.latched("n-1") shouldBe false
  }

  it should "stay unlatched and retry on the next tick while an alias is missing" in {
    val rec    = new Recorder
    val reg    = new AttachStatusRegistry(baseBackoffMs = 0L, maxBackoffMs = 0L)
    val (v, _) = verifier(List(iceSrc("sales_lake")), Set("acme_db"), rec.run(Left("boom")), reg)
    v.verify(node).unsafeRunSync()
    v.verify(node).unsafeRunSync()
    rec.sent.get().size shouldBe 2
    reg.failuresFor("n-1").head.attempts shouldBe 2
  }

  it should "skip a retry that is still inside its backoff window" in {
    val rec    = new Recorder
    val reg    = new AttachStatusRegistry(baseBackoffMs = 600000L, maxBackoffMs = 600000L)
    val (v, _) = verifier(List(iceSrc("sales_lake")), Set("acme_db"), rec.run(Left("boom")), reg)
    v.verify(node).unsafeRunSync()
    v.verify(node).unsafeRunSync()
    rec.sent.get().size shouldBe 1
  }

  it should "clear the failure and latch once the catalog comes back" in {
    val reg     = new AttachStatusRegistry(baseBackoffMs = 0L, maxBackoffMs = 0L)
    val recBad  = new Recorder
    val (v1, _) =
      verifier(List(iceSrc("sales_lake")), Set("acme_db"), recBad.run(Left("boom")), reg)
    v1.verify(node).unsafeRunSync()
    reg.failuresFor("n-1") should not be empty

    val recOk   = new Recorder
    val (v2, _) = verifier(List(iceSrc("sales_lake")), Set("acme_db"), recOk.run(Right(())), reg)
    v2.verify(node).unsafeRunSync()
    reg.failuresFor("n-1") shouldBe empty
    reg.latched("n-1") shouldBe true
  }

  it should "ignore sql sources entirely" in {
    val rec    = new Recorder
    val sqlSrc =
      FederatedSource(id = "fs-pg", tenantDbId = "td-1", alias = "pg", setupSql = "ATTACH 'x';")
    val (v, reg) = verifier(List(sqlSrc), Set("acme_db"), rec.run(Right(())))
    v.verify(node).unsafeRunSync()
    rec.sent.get() shouldBe empty
    reg.latched("n-1") shouldBe true
  }

  it should "not query the node at all when the pool declares no iceberg source" in {
    val rec    = new Recorder
    var listed = 0
    val v      = new IcebergAttachVerifier(
      sourcesOf = _ => Nil,
      renderOne = s => IO.pure(""),
      runOnNode = rec.run(Right(())),
      listCatalogs = _ => IO.delay { listed += 1; Right(Set("acme_db")) },
      registry = new AttachStatusRegistry()
    )
    v.verify(node).unsafeRunSync()
    listed shouldBe 0
    rec.sent.get() shouldBe empty
  }

  it should "leave the node unlatched when the catalog listing itself fails" in {
    val rec = new Recorder
    val reg = new AttachStatusRegistry()
    val v   = new IcebergAttachVerifier(
      sourcesOf = _ => List(iceSrc("sales_lake")),
      renderOne = s => IO.pure(""),
      runOnNode = rec.run(Right(())),
      listCatalogs = _ => IO.pure(Left("node unreachable")),
      registry = reg
    )
    v.verify(node).unsafeRunSync()
    reg.latched("n-1") shouldBe false
    rec.sent.get() shouldBe empty
  }

  "aliasSummary" should "report how many nodes are failing" in {
    val reg = new AttachStatusRegistry()
    reg.recordFailure("n-1", "sales_lake", "boom")
    reg.recordAttached("n-2", "sales_lake")
    reg.aliasSummary("sales_lake", Set("n-1", "n-2")).value should include("1 of 2")
  }

  it should "report attached when every node has it" in {
    val reg = new AttachStatusRegistry()
    reg.recordAttached("n-1", "sales_lake")
    reg.aliasSummary("sales_lake", Set("n-1")).value shouldBe "attached"
  }

  it should "report unknown before any node has been verified" in {
    new AttachStatusRegistry().aliasSummary("sales_lake", Set("n-1")).value shouldBe "unknown"
  }
