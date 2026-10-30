package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.sql.RefusedForms
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, RolePermission}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Task 19 part A in the router's pipeline: a refused form is rewritten before routing and before
  * every screen, so the statement the node gets is the rewrite as the metadata filter narrowed it
  * (the filter runs after the rewriter), a superuser's statement is not rewritten, and history
  * keeps the client's text. SUMMARIZE reads its columns back through the transport's own evaluator.
  */
class RefusedFormsRouterSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  private val tenantUser = EffectiveSet(
    RbacUser("u-1", Some("acme"), "alice", "user"),
    Nil,
    Nil,
    List(RolePermission("p-1", "r-1", "memory", "main", "t", "RO")),
    Nil
  )
  private val superuser = EffectiveSet(RbacUser("u-0", None, "root", "admin"), Nil, Nil, Nil, Nil)

  /** A router over one in-memory pool (catalog `memory`, schema `main`) with the metadata filter
    * mounted (unless `filter` is false); every SQL the node is sent, in order, and the answer
    * `answer` gives it.
    */
  private def setup(
      answer: String => QuackResponse = _ => TestArrow.okResponse(),
      filter: Boolean = true,
      journal: ai.starlake.quack.ondemand.telemetry.EventJournal =
        ai.starlake.quack.ondemand.telemetry.EventJournal.noop,
      columns: ai.starlake.quack.edge.cls.ColumnPolicyRewriter =
        new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
          new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty)
        ),
      attached: Set[String] = Set.empty
  ) =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          25000 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(ai.starlake.quack.model.Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val sent   = scala.collection.mutable.ListBuffer.empty[String]
    val client = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        IO { sent.synchronized(sent += sql); answer(sql) }
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      journal = journal,
      columnPolicyRewriter = columns,
      attachedCatalogsOf = _ => attached,
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = filter)
    )
    (router, () => sent.synchronized(sent.toList))

  private def run(router: FlightSqlRouter, sql: String, eff: EffectiveSet) =
    router.execute("c-1", eff.user.username, poolKey, sql, Some(eff)).unsafeRunSync() match
      case Right(r) => r.close(); Right(r)
      case Left(f)  => Left(f)

  private val Path = RefusedForms.SessionPath("memory", "main")

  "a tenant's SHOW TABLES" should "reach the node as the rewrite the metadata filter narrowed" in {
    val (router, sent) = setup()
    run(router, "SHOW TABLES", tenantUser).isRight shouldBe true
    val sql = sent().last
    sql should startWith("USE memory.main; ")
    sql should not include "SHOW"
    // The rewrite's own predicate, then the filter's derived table around each catalog function:
    // the filter saw the rewritten statement, so it ran after the rewriter.
    sql should include("lower(schema_name) = lower('main')")
    sql should include("(SELECT * FROM duckdb_tables() WHERE database_name = 'memory'")
    sql should include("(SELECT * FROM duckdb_views() WHERE database_name = 'memory'")
    sql should include("table_name = 't'")
  }

  "a tenant's DESCRIBE" should "reach the node as the listing that reads the table" in {
    val (router, sent) = setup()
    run(router, "DESCRIBE t", tenantUser).isRight shouldBe true
    val sql = sent().last
    sql should include("AND NOT EXISTS (SELECT 1 FROM \"memory\".\"main\".\"t\" LIMIT 0)")
    sql should include("(SELECT * FROM information_schema.columns WHERE")
    sql should include("table_name = 't'")
    sql should not include "DESCRIBE"
    // History keeps what the client sent.
    router.history.snapshot(5).headOption.map(r => (r.sql, r.status)) shouldBe
      Some(("DESCRIBE t", "ok"))
  }

  "a superuser's statement" should "reach the node as written" in {
    val (router, sent) = setup()
    run(router, "DESCRIBE t", superuser).isRight shouldBe true
    sent().last should endWith("DESCRIBE t")
    run(router, "SHOW TABLES", superuser).isRight shouldBe true
    sent().last should endWith("; SHOW TABLES")
  }

  "a statement the rewriter does not recognize" should "reach the node unchanged" in {
    val (router, sent) = setup()
    run(router, "DESCRIBE (SELECT 1)", tenantUser).isRight shouldBe true
    sent().last should endWith("DESCRIBE (SELECT 1)")
  }

  /** The node's answer to the SUMMARIZE's column listing: `id INTEGER`, `v VARCHAR`. */
  private val columns: String => QuackResponse = sql =>
    if sql.contains("information_schema.columns") then
      QuackResponse.Ok(
        TestArrow.readerFor(
          "SELECT * FROM (VALUES ('id', 'INTEGER', 'NO', NULL, NULL, NULL), " +
            "('v', 'VARCHAR', 'YES', NULL, NULL, NULL)) AS c(column_name, column_type, \"null\", " +
            "\"key\", \"default\", extra)"
        ),
        1L,
        () => ()
      )
    else TestArrow.okResponse()

  "a tenant's SUMMARIZE" should "read its columns, then send DuckDB's summary query" in {
    val (router, sent) = setup(columns)
    run(router, "SUMMARIZE t", tenantUser).isRight shouldBe true
    val List(listing, summary) = sent(): @unchecked
    listing should include("NOT EXISTS (SELECT 1 FROM \"memory\".\"main\".\"t\" LIMIT 0)")
    summary should endWith(
      RefusedForms.summarizeSql(
        RefusedForms.Target("memory", "main", "t"),
        List(("id", "INTEGER"), ("v", "VARCHAR"))
      )
    )
    // One history row, the client's; the listing is the manager's own read.
    router.history.snapshot(5).map(r => (r.sql, r.status)) shouldBe List(("SUMMARIZE t", "ok"))
  }

  it should "answer the listing's refusal and send nothing more" in {
    val (router, sent) = setup(_ =>
      QuackResponse.Failed(
        QuackError.Permanent("Permission Error: QoD: user:u-1 lacks grants on memory.main.t:read")
      )
    )
    run(router, "SUMMARIZE t", tenantUser) match
      case Left(f) => f.reason should include("lacks grants on memory.main.t:read")
      case other   => fail(s"expected the listing's refusal, got $other")
    sent().size shouldBe 1
    // The node's refusal is a denial (FlightSqlRouter.isNodeRefusal).
    router.history.snapshot(5).map(r => (r.sql, r.status)) shouldBe List(
      ("SUMMARIZE t", "denied")
    )
  }

  it should "stay unchanged on a transport with no evaluator" in {
    val (router, sent) = setup()
    val out            = router
      .executeWith(
        "c-2",
        "alice",
        poolKey,
        "SUMMARIZE t",
        Some(tenantUser),
        (node, wrappedSql, _, _, principal) =>
          router.adapter.send(node, wrappedSql, principal, None).map(NodeOutcome.fromQuackResponse),
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync()
    out.foreach(_.close())
    sent().last should endWith("SUMMARIZE t")
  }

  "the session path" should "be the router's USE prelude target" in {
    val (router, _) = setup()
    router.supervisor.get(poolKey).flatMap(FlightSqlRouter.sessionPath) shouldBe Some(Path)
    router.supervisor.get(poolKey).flatMap(FlightSqlRouter.usePath) shouldBe Some("memory.main")
  }

  // ---- Task 19 part D: computed file locations

  private val Computed  = "SELECT * FROM read_csv('s3://acme-etl/in/' || 'x.csv', header = true)"
  private val Evaluated = "SELECT ('s3://acme-etl/in/' || 'x.csv') AS location"

  /** The node evaluating `value` for every evaluation, answering anything else with one row. */
  private def evaluatesTo(value: String): String => QuackResponse = sql =>
    if sql.endsWith(" AS location") then
      QuackResponse.Ok(TestArrow.readerFor(s"SELECT $value AS location"), 1L, () => ())
    else TestArrow.okResponse()

  "a tenant's computed file location" should "be evaluated by the node, then sent as a literal" in {
    val (router, sent) = setup(evaluatesTo("'s3://acme-etl/in/x.csv'"))
    run(router, Computed, tenantUser).isRight shouldBe true
    val List(evaluation, statement) = sent(): @unchecked
    evaluation should endWith(Evaluated)
    statement should endWith("SELECT * FROM read_csv('s3://acme-etl/in/x.csv', header = true)")
    router.history.snapshot(5).map(r => (r.sql, r.status)) shouldBe List((Computed, "ok"))
  }

  it should "be sent unchanged when the evaluation fails or yields no location" in
    List[String => QuackResponse](
      _ =>
        QuackResponse.Failed(
          QuackError.Permanent("QoD: current_setting is reserved to superusers")
        ),
      evaluatesTo("NULL::VARCHAR"),
      evaluatesTo("''"),
      evaluatesTo("42")
    ).foreach { answer =>
      val (router, sent) = setup(answer)
      run(router, Computed, tenantUser)
      sent().last should endWith(Computed)
    }

  it should "not be evaluated for a superuser" in {
    val (router, sent) = setup(evaluatesTo("'s3://acme-etl/in/x.csv'"))
    run(router, Computed, superuser).isRight shouldBe true
    sent().size shouldBe 1
    sent().last should endWith(Computed)
  }

  /** The Arrow transport's evaluator and relay, as a test passes them to `executeWith`. */
  private def transport(router: FlightSqlRouter) =
    val evaluate: FlightSqlRouter.NodeSend[NodeRows] = (node, wrappedSql, _, _, principal) =>
      router.adapter.send(node, wrappedSql, principal, None).map {
        case QuackResponse.Ok(rows, ms, close) =>
          try
            NodeRows.read(rows).fold(NodeOutcome.Permanent(_, ms), NodeOutcome.Ok(_, ms, () => ()))
          finally close()
        case QuackResponse.Failed(QuackError.Permanent(m), ms) => NodeOutcome.Permanent(m, ms)
        case QuackResponse.Failed(QuackError.Transient(m), ms) => NodeOutcome.Transient(m, ms)
      }
    val relay: FlightSqlRouter.NodeSend[Unit] = (node, wrappedSql, _, _, principal) =>
      router.adapter.send(node, wrappedSql, principal, None).map {
        case QuackResponse.Ok(_, ms, close) => NodeOutcome.Ok((), ms, close)
        case QuackResponse.Failed(err, ms)  => NodeOutcome.Permanent(err.toString, ms)
      }
    (evaluate, relay)

  "inside a transaction on a front door that fails there (items 25-25b)" should
    "evaluate the location and answer every failure, never the unchanged statement" in {
      def scenario(answer: String => QuackResponse, sql: String = Computed) =
        val (router, sent)          = setup(answer)
        val (evaluate, relay)       = transport(router)
        val fenced                  = scala.collection.mutable.ListBuffer.empty[String]
        def statement(text: String) =
          router
            .executeWith(
              "c-tx",
              "alice",
              poolKey,
              text,
              Some(tenantUser),
              relay,
              evaluate = Some(evaluate),
              failInTransaction = Some(answer => IO(fenced += answer).void),
              purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
            )
            .unsafeRunSync()
        statement("BEGIN").foreach(_.close())
        router.sessions.get("c-tx").exists(_.txOpen) shouldBe true
        val out = statement(sql)
        out.foreach(_.close())
        (out, sent(), fenced.toList, router)

      // Evaluated on the transaction's own transport, then sent as a literal.
      val (ok, okSent, okFenced, _) = scenario(evaluatesTo("'s3://acme-etl/in/x.csv'"))
      ok.isRight shouldBe true
      okSent.last should endWith("read_csv('s3://acme-etl/in/x.csv', header = true)")
      okFenced shouldBe Nil

      // A failure the node answers: its own error, prefixed; the statement is never sent.
      val nodeError: String => QuackResponse = sql =>
        if sql.endsWith(" AS location") then
          QuackResponse.Failed(QuackError.Permanent("Binder Error: column t.v not found"))
        else TestArrow.okResponse()
      val (failed, failedSent, failedFenced, router) = scenario(nodeError)
      failed.left.map(_.reason) shouldBe
        Left("computing the file location: permanent failure: Binder Error: column t.v not found")
      failedSent.exists(_.endsWith(Computed)) shouldBe false
      failedFenced shouldBe Nil
      router.history.snapshot(5).headOption.map(r => (r.sql, r.status)) shouldBe
        Some((Computed, "permanent"))

      // The node's refusal of the evaluation: a denial, answered as such; the statement is never
      // sent, and the router fences nothing (the node's own answer decides the transaction).
      val nodeRefusal: String => QuackResponse = sql =>
        if sql.endsWith(" AS location") then
          QuackResponse.Failed(
            QuackError.Permanent(
              "Permission Error: QoD: function current_setting is superuser-only"
            )
          )
        else TestArrow.okResponse()
      val (refused, refusedSent, refusedFenced, _) = scenario(
        nodeRefusal,
        "SELECT * FROM read_csv(current_setting('home_directory') || '/x.csv')"
      )
      refused.left.map(_.reason) shouldBe Left(
        "computing the file location: access denied: Permission Error: QoD: function " +
          "current_setting is superuser-only"
      )
      refusedSent.exists(_.endsWith("|| '/x.csv')")) shouldBe false
      refusedFenced shouldBe Nil

      // A value no literal may stand for: the transaction is fenced with the answer.
      val (rejected, rejectedSent, rejectedFenced, _) = scenario(evaluatesTo("NULL::VARCHAR"))
      val answer                                      =
        "transaction aborted: computing the file location: the location is NULL; ROLLBACK to continue"
      rejected.left.map(_.reason) shouldBe Left(answer)
      rejectedFenced shouldBe List(answer)
      rejectedSent.exists(_.endsWith(Computed)) shouldBe false
    }

  "the Arrow path" should "evaluate in a transaction too, and fall back there on a failure" in {
    val (router, sent) = setup(evaluatesTo("NULL::VARCHAR"))
    router.execute("c-ftx", "alice", poolKey, "BEGIN", Some(tenantUser)).unsafeRunSync()
    router.sessions.get("c-ftx").exists(_.txOpen) shouldBe true
    router
      .execute("c-ftx", "alice", poolKey, Computed, Some(tenantUser))
      .unsafeRunSync()
      .foreach(_.close())
    sent().exists(_.endsWith(Evaluated)) shouldBe true
    sent().last should endWith(Computed)
  }

  "an evaluation" should "be in the kill registry while it runs, and gone after" in {
    var router: FlightSqlRouter = null
    var seen                    = List.empty[String]
    val (r, _)                  = setup { sql =>
      if sql.endsWith(" AS location") then seen = router.registry.list().map(_.sql)
      evaluatesTo("'s3://acme-etl/in/x.csv'")(sql)
    }
    router = r
    run(router, Computed, tenantUser).isRight shouldBe true
    seen shouldBe List(Evaluated)
    router.registry.list() shouldBe Nil
  }

  it should "stop the statement when it is killed while it runs (a PAT revoked meanwhile)" in {
    var router: FlightSqlRouter = null
    val (r, sent)               = setup { sql =>
      // The revocation lands while the node evaluates: the entry has no cancel attached yet.
      if sql.endsWith(" AS location") then router.registry.killByPats(Set("pat-1"))
      evaluatesTo("'s3://acme-etl/in/x.csv'")(sql)
    }
    router = r
    router
      .execute("c-kill", "alice", poolKey, Computed, Some(tenantUser), patId = Some("pat-1"))
      .unsafeRunSync() match
      case Left(f)  => f.reason should include(FlightSqlRouter.KilledReason)
      case Right(r) => r.close(); fail("a killed evaluation's statement ran")
    // The evaluation only: neither the substituted statement nor the client's own was sent.
    sent().count(_.endsWith(Evaluated)) shouldBe 1
    sent().exists(s => !s.endsWith(Evaluated)) shouldBe false
    router.registry.list() shouldBe Nil
  }

  it should "keep a transaction's pin when it fails transiently" in {
    val (router, _) = setup { sql =>
      if sql.endsWith(" AS location") then QuackResponse.Failed(QuackError.Transient("gone"))
      else TestArrow.okResponse()
    }
    router.execute("c-pin", "alice", poolKey, "BEGIN", Some(tenantUser)).unsafeRunSync()
    val pinned = router.sessions.get("c-pin").flatMap(_.pinnedNodeId)
    pinned should not be None
    router
      .execute("c-pin", "alice", poolKey, Computed, Some(tenantUser))
      .unsafeRunSync()
      .foreach(_.close())
    router.sessions.get("c-pin").flatMap(_.pinnedNodeId) shouldBe pinned
  }

  "the audit trail" should "name the location a computed COPY wrote" in {
    val store       = new ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
    val journal     = new ai.starlake.quack.ondemand.telemetry.EventJournal(store)
    val (router, _) = setup(evaluatesTo("'s3://acme-etl/out/x.csv'"), journal = journal)
    run(
      router,
      "COPY (SELECT 1 AS a) TO ('s3://acme-etl/out/' || 'x.csv')",
      tenantUser
    ).isRight shouldBe
      true
    journal.drainNow()
    val write = store.events.find(_.family == "data-write").getOrElse(fail("no data-write event"))
    write.detail.get("locations") shouldBe Some("'s3://acme-etl/out/x.csv'")
    write.detail.get("sql") shouldBe Some(
      "COPY (SELECT 1 AS a) TO ('s3://acme-etl/out/' || 'x.csv')"
    )
  }

  it should "cut the locations it names to 500 characters, like the sql" in {
    // The values are the user's to choose: up to 16 locations of 4 KiB each.
    val store       = new ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
    val journal     = new ai.starlake.quack.ondemand.telemetry.EventJournal(store)
    val long        = "s3://acme-etl/out/" + "x" * 2000 + ".csv"
    val (router, _) = setup(evaluatesTo(s"'$long'"), journal = journal)
    run(
      router,
      "COPY (SELECT 1 AS a) TO ('s3://acme-etl/out/' || 'x.csv')",
      tenantUser
    ).isRight shouldBe true
    journal.drainNow()
    val write = store.events.find(_.family == "data-write").getOrElse(fail("no data-write event"))
    write.detail.get("locations").map(_.length) shouldBe Some(500)
  }

  "SHOW TABLES" should "reach the node as written when the metadata filter does not narrow it" in {
    val (router, sent) = setup(filter = false)
    run(router, "SHOW TABLES", tenantUser).isRight shouldBe true
    sent().last should endWith("SHOW TABLES")
    val all = EffectiveSet(
      RbacUser("u-2", Some("acme"), "bob", "user"),
      Nil,
      Nil,
      List(RolePermission("p-2", "r-2", "*", "*", "*", "ALL")),
      Nil
    )
    val (router2, sent2) = setup()
    run(router2, "SHOW ALL TABLES", all).isRight shouldBe true
    sent2().last should endWith("SHOW ALL TABLES")
  }

  "a two-part name whose head is a catalog" should "reach the node unchanged" in {
    val (router, sent) = setup(attached = Set("pg_fed"))
    run(router, "DESCRIBE pg_fed.customers", tenantUser).isRight shouldBe true
    sent().last should endWith("DESCRIBE pg_fed.customers")
    run(router, "DESCRIBE memory.t", tenantUser).isRight shouldBe true
    sent().last should endWith("DESCRIBE memory.t")
  }

  // ---- I2: column policies apply to the rewrites' reads

  private val maskedUser = EffectiveSet(
    RbacUser("u-3", Some("acme"), "carol", "user"),
    Nil,
    Nil,
    List(RolePermission("p-3", "r-3", "memory", "main", "customer", "RO")),
    Nil,
    List(
      ai.starlake.quack.ondemand.state.RoleColumnPolicy(
        "cp-3",
        "r-3",
        "*",
        "main",
        "customer",
        "c_email",
        "mask",
        Some("'***'")
      ),
      ai.starlake.quack.ondemand.state.RoleColumnPolicy(
        "cp-4",
        "r-3",
        "*",
        "main",
        "customer",
        "c_secret",
        "deny",
        None
      )
    )
  )

  private val customerColumns = new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
    new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
      Map(("memory", "main", "customer") -> List("c_id", "c_email", "c_secret"))
    )
  )

  "a SUMMARIZE over a masked column" should "aggregate the masked values" in {
    val listing: String => QuackResponse = sql =>
      if sql.contains("information_schema.columns") then
        QuackResponse.Ok(
          TestArrow.readerFor(
            "SELECT * FROM (VALUES ('c_id', 'INTEGER'), ('c_email', 'VARCHAR')) " +
              "AS c(column_name, column_type)"
          ),
          1L,
          () => ()
        )
      else TestArrow.okResponse()
    val (router, sent) = setup(listing, columns = customerColumns)
    // A denied column refuses the summary, as it refuses SELECT * of the table.
    run(router, "SUMMARIZE customer", maskedUser).left.map(_.reason) shouldBe
      Left("access denied: column customer.c_secret is denied")
    val maskOnly = maskedUser.copy(columnPolicies = maskedUser.columnPolicies.take(1))
    val out      = run(router, "SUMMARIZE customer", maskOnly)
    withClue(out)(out.isRight shouldBe true)
    val summary = sent().last
    summary should include("unnest(list_value('c_id', 'c_email'))")
    // Every aggregate of c_email reads the mask, and the derived table they read masks it too.
    summary should include("CAST(min('***') AS VARCHAR)")
    summary should include("CAST(max('***') AS VARCHAR)")
    summary should include("approx_count_distinct('***')")
    summary should include("'***' AS c_email")
    summary should not include "min(\"c_email\")"
  }

  "a DESCRIBE of a table with a denied column" should "not be refused" in {
    val (router, sent) = setup(columns = customerColumns)
    val out            = run(router, "DESCRIBE customer", maskedUser)
    withClue(out)(out.isRight shouldBe true)
    sent().last should include("NOT EXISTS (SELECT 1 FROM")
  }
