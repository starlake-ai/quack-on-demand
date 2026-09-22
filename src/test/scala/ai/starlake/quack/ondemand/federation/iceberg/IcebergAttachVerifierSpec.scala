package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType, PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.federation.ResolvedFederationBlock
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.{Field, FieldType, Schema}
import org.apache.arrow.vector.types.pojo.ArrowType
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

  private val startedAtMs = node.startedAt.toEpochMilli

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
      sourcesOf = _ => IO.pure(sources),
      renderOne = s => IO.pure(ResolvedFederationBlock(s"-- rendered ${s.alias}", Set.empty)),
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
    reg.latched(node.nodeId, startedAtMs) shouldBe true
    reg.failuresFor(node.nodeId, startedAtMs) shouldBe empty
    // I1: the presence path must record success too, or the operator summary reports "unknown"
    // forever for a catalog that is attached and healthy.
    reg.aliasSummary("sales_lake", Set(node.nodeId)).value shouldBe "attached"
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
    reg.latched(node.nodeId, startedAtMs) shouldBe true
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
    val f = reg.failuresFor(node.nodeId, startedAtMs).head
    f.alias shouldBe "sales_lake"
    f.error should include("Could not get token")
    f.attempts shouldBe 1
    reg.latched(node.nodeId, startedAtMs) shouldBe false
  }

  // The error text DuckDB hands back carries the catalog's HTTP response body verbatim, and a
  // catalog that echoes the `Authorization: Basic base64(client_id:client_secret)` header it
  // received therefore puts the plaintext client secret in it. Redaction happens BEFORE the
  // registry stores it (and before the WARN), because both of those outlive the request.
  it should "scrub the rendered block's credentials out of the error it stores" in {
    val secret = "SENTINEL_CLIENT_SECRET_AAA111"
    val basic  = java.util.Base64.getEncoder.encodeToString(
      s"cid:$secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    val reg = new AttachStatusRegistry()
    val v   = new IcebergAttachVerifier(
      sourcesOf = _ => IO.pure(List(iceSrc("sales_lake"))),
      renderOne = _ =>
        IO.pure(
          ResolvedFederationBlock(
            "CREATE OR REPLACE SECRET \"qod_ice_sales\" (\n  TYPE ICEBERG,\n" +
              s"  CLIENT_ID 'cid',\n  CLIENT_SECRET '$secret'\n);",
            Set(secret)
          )
        ),
      runOnNode = (_, _) =>
        IO.pure(
          Left(
            "Invalid Configuration Error: Could not get token from https://idp/v1/oauth/tokens: " +
              s"""HTTP Unauthorized_401 - {"message": "rejected Basic $basic, secret was $secret"}"""
          )
        ),
      listCatalogs = _ => IO.pure(Right(Set("acme_db"))),
      registry = reg
    )
    v.verify(node).unsafeRunSync()
    val stored = reg.failuresFor(node.nodeId, startedAtMs).head.error
    stored should not include secret
    stored should not include basic
    // The diagnostic itself must survive: redaction is surgical, not a blanket drop.
    stored should include("Could not get token from https://idp/v1/oauth/tokens")
    stored should include("Unauthorized_401")
  }

  // The credential set the verifier scrubs with comes from the BUILDER (the component that
  // resolved the secrets), not from parsing the SQL back out. This pins that wiring: the secret
  // here sits in an option the SQL parser deliberately does not treat as credential-bearing
  // (CLIENT_ID), so it is scrubbed only because `ResolvedFederationBlock.secretValues` carried it.
  // Reverting `note`'s credential argument to `AttachErrorRedactor.credentialsIn(block.sql)` alone
  // leaves the value in the registry.
  it should "scrub a substituted value the SQL parser would not classify as a credential" in {
    // Lowercase and hyphenated on purpose: `scrub`'s blanket arm masks encoded-LOOKING runs, so a
    // mixed-case value with digits would be redacted even with an empty credential set and this
    // test would pass for the wrong reason. This value survives every shape rule, so the only
    // thing that can redact it is the credential set the builder reported.
    val resolved = "resolved-secret-value"
    val reg      = new AttachStatusRegistry()
    val v        = new IcebergAttachVerifier(
      sourcesOf = _ => IO.pure(List(iceSrc("sales_lake"))),
      renderOne = _ =>
        IO.pure(
          ResolvedFederationBlock(
            s"CREATE OR REPLACE SECRET \"qod_ice_sales\" (\n  CLIENT_ID '$resolved'\n);",
            Set(resolved)
          )
        ),
      runOnNode = (_, _) => IO.pure(Left(s"HTTP Unauthorized_401 - echoed $resolved")),
      listCatalogs = _ => IO.pure(Right(Set("acme_db"))),
      registry = reg
    )
    v.verify(node).unsafeRunSync()
    val stored = reg.failuresFor(node.nodeId, startedAtMs).head.error
    stored should not include resolved
    AttachErrorRedactor.credentialsIn(
      s"CREATE OR REPLACE SECRET \"qod_ice_sales\" (\n  CLIENT_ID '$resolved'\n);"
    ) shouldBe empty
  }

  // ---------- locale-independent alias folding ----------
  // Every alias fold in this package goes through Locale.ROOT. This is the ONE place a revert of
  // that is observable: `failuresFor` sorts on the folded alias, and under a Turkish default
  // locale `"I".toLowerCase` is the dotless `i` (U+0131), which sorts AFTER `z` instead of before
  // it. The package's other folded sites fold both sides of their comparison with the same call,
  // so reverting those changes no outcome and no honest test can pin them.
  //
  // This sets the JVM default locale and restores it in `finally`. Tests in this build run
  // sequentially inside one forked JVM (`Test / fork := true`, `testForkedParallel` unset), so
  // nothing else observes the window.
  it should "order attach failures by a locale-independent fold of the alias" in {
    val previous = java.util.Locale.getDefault
    try
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
      val reg = new AttachStatusRegistry()
      reg.recordFailure("n-loc", 1000L, "SALES_I", "boom")
      reg.recordFailure("n-loc", 1000L, "sales_z", "boom")
      reg.failuresFor("n-loc", 1000L).map(_.alias) shouldBe List("SALES_I", "sales_z")
    finally java.util.Locale.setDefault(previous)
  }

  it should "report a legacy mixed-case alias as declared, not lowercased" in {
    val rec      = new Recorder
    val (v, reg) = verifier(List(iceSrc("Sales_Lake")), Set("acme_db"), rec.run(Left("boom")))
    v.verify(node).unsafeRunSync()
    reg.failuresFor(node.nodeId, startedAtMs).map(_.alias) shouldBe List("Sales_Lake")
    // The lookup key stays normalized, so the operator summary still finds it.
    reg.aliasSummary("sales_lake", Set(node.nodeId)).value shouldBe "failed on 1 of 1 nodes"
  }

  it should "stay unlatched and retry on the next tick while an alias is missing" in {
    val rec    = new Recorder
    val reg    = new AttachStatusRegistry(baseBackoffMs = 0L, maxBackoffMs = 0L)
    val (v, _) = verifier(List(iceSrc("sales_lake")), Set("acme_db"), rec.run(Left("boom")), reg)
    v.verify(node).unsafeRunSync()
    v.verify(node).unsafeRunSync()
    rec.sent.get().size shouldBe 2
    reg.failuresFor(node.nodeId, startedAtMs).head.attempts shouldBe 2
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
    reg.failuresFor(node.nodeId, startedAtMs) should not be empty

    val recOk   = new Recorder
    val (v2, _) = verifier(List(iceSrc("sales_lake")), Set("acme_db"), recOk.run(Right(())), reg)
    v2.verify(node).unsafeRunSync()
    reg.failuresFor(node.nodeId, startedAtMs) shouldBe empty
    reg.latched(node.nodeId, startedAtMs) shouldBe true
  }

  it should "ignore sql sources entirely" in {
    val rec    = new Recorder
    val sqlSrc =
      FederatedSource(id = "fs-pg", tenantDbId = "td-1", alias = "pg", setupSql = "ATTACH 'x';")
    val (v, reg) = verifier(List(sqlSrc), Set("acme_db"), rec.run(Right(())))
    v.verify(node).unsafeRunSync()
    rec.sent.get() shouldBe empty
    reg.latched(node.nodeId, startedAtMs) shouldBe true
  }

  it should "not query the node at all when the pool declares no iceberg source" in {
    val rec    = new Recorder
    var listed = 0
    val v      = new IcebergAttachVerifier(
      sourcesOf = _ => IO.pure(Nil),
      renderOne = _ => IO.pure(ResolvedFederationBlock("", Set.empty)),
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
      sourcesOf = _ => IO.pure(List(iceSrc("sales_lake"))),
      renderOne = _ => IO.pure(ResolvedFederationBlock("", Set.empty)),
      runOnNode = rec.run(Right(())),
      listCatalogs = _ => IO.pure(Left("node unreachable")),
      registry = reg
    )
    v.verify(node).unsafeRunSync()
    reg.latched(node.nodeId, startedAtMs) shouldBe false
    rec.sent.get() shouldBe empty
  }

  // I3: a FAILED source lookup must not be treated as "nothing declared" (which would latch the
  // node forever). Mutating sourcesOf's Left arm back into `.getOrElse(Nil)` semantics would pass
  // this as latched=true; here it must stay unlatched, and the node must never be queried since we
  // never learned what it should have.
  it should "leave the node unlatched when the source lookup itself fails" in {
    val rec = new Recorder
    val reg = new AttachStatusRegistry()
    val v   = new IcebergAttachVerifier(
      sourcesOf = _ => IO.raiseError(new RuntimeException("postgres unreachable")),
      renderOne = _ => IO.pure(ResolvedFederationBlock("", Set.empty)),
      runOnNode = rec.run(Right(())),
      listCatalogs = _ => IO.pure(Right(Set("acme_db"))),
      registry = reg
    )
    v.verify(node).unsafeRunSync()
    reg.latched(node.nodeId, startedAtMs) shouldBe false
    rec.sent.get() shouldBe empty
  }

  // C1 revert proof: if `sourcesOf(node.poolKey)` is ever evaluated OUTSIDE the returned IO again
  // (the original bug), a `sourcesOf` that throws SYNCHRONOUSLY would blow up at the `verify(node)`
  // call site itself, before any IO runs -- exactly the exception that killed the whole HealthProbe
  // fiber. With the fix, `verify` must return a plain IO value without throwing, and the exception
  // must only surface when that IO is actually run.
  it should "not throw synchronously when sourcesOf itself throws, only fail the returned IO" in {
    val v = new IcebergAttachVerifier(
      sourcesOf = _ => throw new RuntimeException("boom"),
      renderOne = _ => IO.pure(ResolvedFederationBlock("", Set.empty)),
      runOnNode = (_, _) => IO.pure(Right(())),
      listCatalogs = _ => IO.pure(Right(Set.empty)),
      registry = new AttachStatusRegistry()
    )
    val effect = v.verify(node) // must not throw here
    a[RuntimeException] should be thrownBy effect.unsafeRunSync()
  }

  // C2 revert proof: PoolSupervisor.nodeId is a deterministic slot id reused by respawnSpec, so a
  // respawned node reuses "n-1". If the latch keyed on nodeId alone, the second incarnation would
  // short-circuit at the very first `registry.latched` check and never be queried at all -- exactly
  // the bug that makes a scale-to-zero resume with expired credentials invisible.
  it should "verify a respawned node under the same nodeId as a fresh incarnation" in {
    val reg  = new AttachStatusRegistry()
    val gen1 = node.copy(startedAt = Instant.ofEpochMilli(1_000L))
    val gen2 = node.copy(startedAt = Instant.ofEpochMilli(2_000L))

    val (v1, _) =
      verifier(
        List(iceSrc("sales_lake")),
        Set("acme_db", "sales_lake"),
        new Recorder().run(Right(())),
        reg
      )
    v1.verify(gen1).unsafeRunSync()
    reg.latched(gen1.nodeId, gen1.startedAt.toEpochMilli) shouldBe true

    val rec2    = new Recorder
    val (v2, _) =
      verifier(List(iceSrc("sales_lake")), Set("acme_db"), rec2.run(Left("token expired")), reg)
    v2.verify(gen2).unsafeRunSync()

    rec2.sent.get() should not be empty
    reg.latched(gen2.nodeId, gen2.startedAt.toEpochMilli) shouldBe false
    reg.failuresFor(gen2.nodeId, gen2.startedAt.toEpochMilli) should not be empty
  }

  // I4: alias matching must be case-insensitive end to end. Two independent tests are required
  // to cover both directions of the case-normalization logic (present.map(_.toLowerCase) and
  // s.alias.toLowerCase); no single fixture can trigger all three mutations. This first test pins
  // the present-side normalization, catching mutation of present.map(_.toLowerCase). Declared
  // alias "Sales_Lake" (mixed case at spawn), present set "Sales_Lake" (node reports unchanged).
  // Removing present-side toLowerCase sends the alias to `missing`, re-attaching forever.
  // Production case: SqlLiterals.duckdbIdent always double-quotes, so DuckDB reports the exact
  // spawn-time case, while the REST handler normalizes to lowercase without restarting nodes.
  it should "normalize alias case when the node reports spawn-time case and the row is " +
    "normalized" in {
      val rec      = new Recorder
      val (v, reg) =
        verifier(List(iceSrc("Sales_Lake")), Set("acme_db", "Sales_Lake"), rec.run(Right(())))
      v.verify(node).unsafeRunSync()
      rec.sent.get() shouldBe empty
      reg.aliasSummary("sales_lake", Set(node.nodeId)).value shouldBe "attached"
    }

  // Second direction: this fixture pins the alias-side normalization, catching mutation of
  // s.alias.toLowerCase. Declared alias "sales_lake" (lowercase after normalization), present
  // set "Sales_Lake" (node still reports mixed case). Removing alias-side toLowerCase sends
  // the alias to `missing`, re-attaching forever.
  it should "normalize alias case when the row keeps mixed case and the node reports it " +
    "lowercased" in {
      val rec      = new Recorder
      val (v, reg) =
        verifier(List(iceSrc("sales_lake")), Set("acme_db", "Sales_Lake"), rec.run(Right(())))
      v.verify(node).unsafeRunSync()
      rec.sent.get() shouldBe empty
      reg.aliasSummary("sales_lake", Set(node.nodeId)).value shouldBe "attached"
    }

  // Important 1 (fix review): pruneOtherIncarnations had zero coverage -- replacing its body with
  // `()` passed the whole suite. This drives it through the real `verify` path (not a raw registry
  // call) so it also proves prune runs as part of a normal tick, and checks the "another node's
  // entries survive" half of the contract at the same time.
  it should "prune a node's earlier incarnation once its successor is verified, " +
    "without touching another node's entries" in {
      val reg = new AttachStatusRegistry()
      reg.recordFailure("n-1", 1000L, "sales_lake", "boom")
      reg.recordFailure("n-2", 1000L, "sales_lake", "boom")

      val gen2   = node.copy(startedAt = Instant.ofEpochMilli(2000L))
      val (v, _) = verifier(
        List(iceSrc("sales_lake")),
        Set("acme_db", "sales_lake"),
        new Recorder().run(Right(())),
        reg
      )
      v.verify(gen2).unsafeRunSync()

      reg.latched(gen2.nodeId, 2000L) shouldBe true
      reg.failuresFor("n-1", 1000L) shouldBe empty
      reg.failuresFor("n-2", 1000L) should not be empty
    }

  // Important 4 (fix review): the exponential backoff used to gate only the re-attach inside
  // `reattach`, not the lookup that precedes it -- a permanently broken catalog paid one Postgres
  // round trip (sourcesOf) and one node round trip (listCatalogs) on EVERY health tick, forever.
  // With the whole-pass gate, once every alias for this incarnation is inside its backoff window,
  // neither is called at all on the next tick.
  it should "skip sourcesOf and listCatalogs entirely while every known failure is still " +
    "inside its backoff window" in {
      val reg         = new AttachStatusRegistry(baseBackoffMs = 600000L, maxBackoffMs = 600000L)
      var sourceCalls = 0
      var listCalls   = 0
      val v           = new IcebergAttachVerifier(
        sourcesOf = _ => IO.delay { sourceCalls += 1; List(iceSrc("sales_lake")) },
        renderOne = _ => IO.pure(ResolvedFederationBlock("", Set.empty)),
        runOnNode = (_, _) => IO.pure(Left("boom")),
        listCatalogs = _ => IO.delay { listCalls += 1; Right(Set("acme_db")) },
        registry = reg
      )
      v.verify(node).unsafeRunSync() // first tick: fails, records a failure, enters backoff
      sourceCalls shouldBe 1
      listCalls shouldBe 1

      v.verify(node)
        .unsafeRunSync() // second tick: still inside backoff, whole pass must be skipped
      sourceCalls shouldBe 1
      listCalls shouldBe 1
    }

  // I5: renderOne can raise (an invalid Iceberg config is a documented, reachable state); that must
  // be captured, not propagated past `verify`.
  it should "record a render failure without throwing when renderOne raises" in {
    val reg = new AttachStatusRegistry()
    val v   = new IcebergAttachVerifier(
      sourcesOf = _ => IO.pure(List(iceSrc("sales_lake"))),
      renderOne = _ => IO.raiseError(new RuntimeException("bad config")),
      runOnNode = (_, _) => IO.pure(Right(())),
      listCatalogs = _ => IO.pure(Right(Set("acme_db"))),
      registry = reg
    )
    v.verify(node).unsafeRunSync()
    reg.failuresFor(node.nodeId, startedAtMs).head.error should include("bad config")
    reg.latched(node.nodeId, startedAtMs) shouldBe false
  }

  it should "ignore sql sources entirely (zero round trips for a pool with only sql sources)" in {
    val rec    = new Recorder
    val sqlSrc =
      FederatedSource(id = "fs-pg2", tenantDbId = "td-1", alias = "pg2", setupSql = "ATTACH 'x';")
    var listed = 0
    val v      = new IcebergAttachVerifier(
      sourcesOf = _ => IO.pure(List(sqlSrc)),
      renderOne = _ => IO.pure(ResolvedFederationBlock("", Set.empty)),
      runOnNode = rec.run(Right(())),
      listCatalogs = _ => IO.delay { listed += 1; Right(Set("acme_db")) },
      registry = new AttachStatusRegistry()
    )
    v.verify(node).unsafeRunSync()
    listed shouldBe 0
    rec.sent.get() shouldBe empty
  }

  "AttachStatusRegistry backoff" should "grow exponentially and cap at maxBackoffMs" in {
    val reg = new AttachStatusRegistry(baseBackoffMs = 1000L, maxBackoffMs = 4000L)

    reg.recordFailure("n", 0L, "a", "boom", nowMs = 0L) // attempts=1, delay=1000
    reg.shouldRetry("n", 0L, "a", nowMs = 999L) shouldBe false
    reg.shouldRetry("n", 0L, "a", nowMs = 1000L) shouldBe true

    reg.recordFailure("n", 0L, "a", "boom", nowMs = 1000L) // attempts=2, delay=2000 (growth)
    reg.shouldRetry("n", 0L, "a", nowMs = 2999L) shouldBe false
    reg.shouldRetry("n", 0L, "a", nowMs = 3000L) shouldBe true

    reg.recordFailure("n", 0L, "a", "boom", nowMs = 3000L) // attempts=3, delay=4000
    reg.shouldRetry("n", 0L, "a", nowMs = 6999L) shouldBe false
    reg.shouldRetry("n", 0L, "a", nowMs = 7000L) shouldBe true

    // attempts=4 would be 8000 uncapped; the ceiling holds it at 4000.
    reg.recordFailure("n", 0L, "a", "boom", nowMs = 7000L)
    reg.shouldRetry("n", 0L, "a", nowMs = 10999L) shouldBe false
    reg.shouldRetry("n", 0L, "a", nowMs = 11000L) shouldBe true
  }

  it should "report a failure as noteworthy only when it is new or its error text changes" in {
    val reg = new AttachStatusRegistry()
    reg.recordFailure("n", 0L, "a", "boom") shouldBe true
    reg.recordFailure("n", 0L, "a", "boom") shouldBe false
    reg.recordFailure("n", 0L, "a", "different boom") shouldBe true
  }

  it should "order failures case-insensitively on the alias" in {
    val reg = new AttachStatusRegistry()
    reg.recordFailure("n", 0L, "Sales_Lake", "boom")
    reg.recordFailure("n", 0L, "analytics", "boom")
    // The declared case is preserved in the payload, but it must not decide the order: sorting on
    // the raw alias puts "Sales_Lake" first in ASCII and last once the same row is normalized, so
    // the operator-visible order would depend on the case of a name compared case-insensitively
    // everywhere else.
    reg.failuresFor("n", 0L).map(_.alias) shouldBe List("analytics", "Sales_Lake")
  }

  "aliasSummary" should "report how many nodes are failing" in {
    val reg = new AttachStatusRegistry()
    reg.recordFailure("n-1", 0L, "sales_lake", "boom")
    reg.recordAttached("n-2", 0L, "sales_lake")
    reg.aliasSummary("sales_lake", Set("n-1", "n-2")).value should include("1 of 2")
  }

  it should "report attached when every node has it" in {
    val reg = new AttachStatusRegistry()
    reg.recordAttached("n-1", 0L, "sales_lake")
    reg.aliasSummary("sales_lake", Set("n-1")).value shouldBe "attached"
  }

  it should "report unknown before any node has been verified" in {
    new AttachStatusRegistry().aliasSummary("sales_lake", Set("n-1")).value shouldBe "unknown"
  }

  // ---- IcebergAttachVerifier.decodeCatalogNames (I6: nodeCatalogs had zero coverage) ----

  /** A hand-rolled ArrowReader driven by a script of batches, each a list of string cell values (an
    * empty list simulates a schema-only batch), so the decode loop can be pinned without a live
    * DuckDB connection. `failAt`, if set, throws from `loadNextBatch()` at that 0-based call index
    * instead of returning a batch.
    */
  private final class ScriptedCatalogReader(batches: List[List[String]], failAt: Option[Int] = None)
      extends ArrowReader(new RootAllocator()):
    private var pos = 0

    override def loadNextBatch(): Boolean =
      if failAt.contains(pos) then throw new java.io.IOException(s"wire error at batch $pos")
      else if pos >= batches.length then false
      else
        val values = batches(pos)
        val root   = getVectorSchemaRoot
        val vec    = root.getFieldVectors.get(0).asInstanceOf[VarCharVector]
        vec.reset()
        vec.allocateNew()
        values.zipWithIndex.foreach { case (v, i) => vec.setSafe(i, v.getBytes("UTF-8")) }
        vec.setValueCount(values.length)
        root.setRowCount(values.length)
        pos += 1
        true

    override def bytesRead(): Long = 0L

    override def closeReadSource(): Unit = ()

    override def readSchema(): Schema =
      new Schema(
        java.util.Collections.singletonList(
          new Field(
            "database_name",
            FieldType.nullable(new ArrowType.Utf8()),
            java.util.Collections.emptyList[Field]()
          )
        )
      )

  // Important 3 (fix review): the only test that inspected `close` drove the failure path, so
  // moving `close()` out of `finally` into the `catch` arm passed the whole suite while leaking the
  // Arrow reader on every SUCCESSFUL catalog listing. Asserting `closed` here too closes that gap.
  "IcebergAttachVerifier.decodeCatalogNames" should "drain and merge every batch of a reader, " +
    "closing the reader on success too" in {
      var closed = false
      val reader =
        new ScriptedCatalogReader(List(List("acme_db"), List("sales_lake", "other_lake")))
      IcebergAttachVerifier.decodeCatalogNames(reader, () => closed = true) shouldBe
        Right(Set("acme_db", "sales_lake", "other_lake"))
      closed shouldBe true
    }

  it should "not stop at a schema-only first batch" in {
    val reader = new ScriptedCatalogReader(List(Nil, List("sales_lake")))
    IcebergAttachVerifier.decodeCatalogNames(reader, () => ()) shouldBe Right(Set("sales_lake"))
  }

  it should "fail soft to a Left, without throwing, when a later batch errors" in {
    val reader = new ScriptedCatalogReader(List(List("acme_db")), failAt = Some(1))
    IcebergAttachVerifier.decodeCatalogNames(reader, () => ()) match
      case Left(err) => err should include("wire error")
      case Right(v)  => fail(s"expected a Left, got Right($v)")
  }

  it should "close the reader on every path, including the failure path" in {
    var closed = false
    val reader = new ScriptedCatalogReader(List(List("acme_db")), failAt = Some(1))
    IcebergAttachVerifier.decodeCatalogNames(reader, () => closed = true)
    closed shouldBe true
  }
