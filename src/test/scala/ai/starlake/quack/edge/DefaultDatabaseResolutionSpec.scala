package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  StatementKind,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacUser,
  RoleColumnPolicy,
  RoleRowPolicy
}
import ai.starlake.quack.ondemand.PoolSupervisor
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.Locale
import scala.collection.concurrent.TrieMap

/** The policy rewriters qualify an unqualified table name with the catalog and schema the node
  * resolves it in: the target of the USE prelude, the database's catalog alias and its session
  * schema, whatever the row's stored `defaultDatabase` says. Each case runs at the rewriter level
  * (the context the router derives) and through the router (FlightSQL, REST and MCP through
  * `execute`, the native front door through `executeWith`), on the bare, schema-qualified and fully
  * qualified forms of one name, which must all be treated alike.
  */
class DefaultDatabaseResolutionSpec extends AnyFlatSpec with Matchers:

  private val Alias    = "acme_rlsprobe"
  private val Other    = "lake2"
  private val probeKey = PoolKey("acme", Alias, "probe")
  private val memKey   = PoolKey("acme", "acme_mem", "probe")

  private val user = RbacUser("u-1", Some("acme"), "probeu", "user")

  private val forms =
    List("t" -> "bare", "main.t" -> "schema-qualified", s"$Alias.main.t" -> "full")

  private def rowPolicyOn(catalog: String) =
    RoleRowPolicy("rp-1", "r-1", catalog, "main", "t", "f = 0")

  private def maskOn(catalog: String) =
    RoleColumnPolicy("cp-1", "r-1", catalog, "main", "t", "f", "mask", Some("'***'"))

  private def withRows(ps: List[RoleRowPolicy]) =
    EffectiveSet(user, Nil, Nil, Nil, Nil, Nil, ps)

  private def withMasks(ps: List[RoleColumnPolicy]) =
    EffectiveSet(user, Nil, Nil, Nil, Nil, ps)

  /** Both catalogs hold a `t` with the same columns, so a mask that matched either would rewrite.
    */
  private def columnCatalog =
    new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(
      Map(
        (Alias, "main", "t") -> List("id", "f"),
        (Other, "main", "t") -> List("id", "f")
      )
    )

  private def filtered(sql: String): Boolean = sql.toLowerCase(Locale.ROOT).contains("f = 0")
  private def masked(sql: String): Boolean   = sql.contains("'***'")

  // ---------- the cases: (stored defaultDatabase, catalog the policy names, expected per form) ----

  /** C: unset, policy on the alias: every form filtered. A: defaultDatabase names another catalog,
    * policy on the alias: every form filtered, as the node reads the alias's `t` for all three. B:
    * policy on the other name: no form filtered, since none of them reads a table in that catalog.
    */
  private val cases: List[(String, Option[String], String, Boolean)] = List(
    ("C (unset)", None, Alias, true),
    ("A (defaultDatabase=lake2, policy on the alias)", Some(Other), Alias, true),
    ("B (defaultDatabase=lake2, policy on lake2)", Some(Other), Other, false)
  )

  // ---------- rewriter level: the context the router derives for the pool ----------

  "the rewriters' defaults" should "be the USE prelude's catalog and schema" in {
    for dd <- List(None, Some(Other)) do
      withClue(s"stored defaultDatabase $dd: ") {
        val (router, _) = routerWith(dd)
        val st          = router.supervisor.get(probeKey).getOrElse(fail("no pool state"))
        val path        = FlightSqlRouter.sessionPath(st).getOrElse(fail("no session path"))
        router.defaultsOf(probeKey) shouldBe ((Some(path.catalog), Some(path.schema)))
        router.defaultsOf(probeKey) shouldBe ((Some(Alias), Some("main")))
      }
  }

  it should "follow a memory database's own catalog, never the stored defaultDatabase" in {
    val (router, sent) = routerWith(Some("fedpg"), memory = true)
    // A memory row resolves to DuckDB's `memory` catalog (effectiveMetastoreFor's dbName), and
    // its defaultSchema still names the session schema.
    router.defaultsOf(memKey) shouldBe ((Some("memory"), Some("public")))
    run(router.execute("c-mem", "probeu", memKey, "SELECT 1 FROM t"))
    UseTarget.findFirstMatchIn(sent()).map(_.group(1)) shouldBe Some("memory.public")
  }

  it should "be empty for a pool the supervisor does not know" in {
    val (router, _) = routerWith(None)
    router.defaultsOf(PoolKey("acme", "acme_nothere", "p")) shouldBe ((None, None))
  }

  for (name, dd, policyCatalog, expected) <- cases do
    it should s"make a row policy apply as the node resolves, case $name" in {
      val (router, _)  = routerWith(dd)
      val (db, schema) = router.defaultsOf(probeKey)
      val ctx          = ai.starlake.quack.edge.rls.SchemaContext(db, schema)
      val rw           = new ai.starlake.quack.edge.rls.RowPolicyRewriter(enabled = true)
      for (table, form) <- forms do
        withClue(s"$form name $table: ") {
          val out = rw.rewrite(
            s"SELECT count(*) FROM $table",
            StatementKind.Select,
            withRows(List(rowPolicyOn(policyCatalog))),
            ctx
          )
          out match
            case ai.starlake.quack.edge.rls.RowPolicyRewriter.Rewritten(sql) =>
              filtered(sql) shouldBe expected
            case ai.starlake.quack.edge.rls.RowPolicyRewriter.Passthrough =>
              expected shouldBe false
            case other => fail(s"unexpected $other")
        }
    }

    it should s"make a column mask apply as the node resolves, case $name" in {
      val (router, _)  = routerWith(dd)
      val (db, schema) = router.defaultsOf(probeKey)
      val ctx = ai.starlake.quack.edge.cls.SchemaContext(db, schema, Set(Alias), Some(probeKey))
      val rw  =
        new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(columnCatalog, enabled = true)
      for (table, form) <- forms do
        withClue(s"$form name $table: ") {
          val out = rw
            .rewrite(
              s"SELECT f FROM $table",
              StatementKind.Select,
              withMasks(List(maskOn(policyCatalog))),
              ctx
            )
            .unsafeRunSync()
          out match
            case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Rewritten(sql) =>
              masked(sql) shouldBe expected
            case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Passthrough =>
              expected shouldBe false
            case other => fail(s"unexpected $other")
        }
    }

  // ---------- router level ----------

  private def backend = new QuackBackend:
    def allowsLocalFiles = true

    private val n          = TrieMap.empty[String, RunningNode]
    def start(s: NodeSpec) = IO {
      val r = RunningNode(
        s.nodeId,
        s.poolKey,
        s.role,
        "127.0.0.1",
        26000 + n.size,
        "tok",
        Some(6L),
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

  /** A duckdb-file database `acme_rlsprobe` (catalog alias `acme_rlsprobe`, schema `main`) with
    * `storedDefault` written to its stored row the way an earlier release let a tenant admin write
    * it, then restored into the pool state, and a router that records the last text a node got.
    */
  private def routerWith(storedDefault: Option[String], memory: Boolean = false) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(backend, new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val (poolKey, created) =
      if memory then
        memKey -> sup.createTenantDb(
          tenantName = "acme",
          suffix = "mem",
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = "",
          defaultSchema = Some("public"),
          writerTrusted = true
        )
      else
        probeKey -> sup.createTenantDb(
          tenantName = "acme",
          suffix = "rlsprobe",
          kind = TenantDbKind.DuckDbFile,
          metastore = Map("dbName" -> Alias, "schemaName" -> "main"),
          dataPath = "/tmp/qod-rlsprobe.duckdb",
          writerTrusted = true
        )
    created.unsafeRunSync().fold(e => fail(s"create failed: ${e.message}"), identity)
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val td = sup.findTenantDb("acme", poolKey.tenantDb).getOrElse(fail("no tenant-db"))
    store.upsertTenantDb(td.copy(defaultDatabase = storedDefault))
    sup.restore()
    sup.findTenantDb("acme", poolKey.tenantDb).flatMap(_.defaultDatabase) shouldBe storedDefault

    var captured = ""
    val tracker  = new NodeLoadTracker
    val client   = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ) =
        captured = sql
        IO.pure(TestArrow.okResponse())
    val adapter =
      new QuackHttpAdapter(client, tracker, ai.starlake.quack.edge.authz.NodeKeys.ephemeral())
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      adapter,
      columnPolicyRewriter =
        new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(columnCatalog, enabled = true),
      metadataFilterRewriter =
        new ai.starlake.quack.edge.meta.MetadataFilterRewriter(enabled = true),
      protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
    )
    (router, () => captured)

  /** How a statement reaches the router: FlightSQL / REST / MCP through `execute` (REST and MCP
    * statements go through `routedExecutor`, which calls it with their own source), the native
    * front door through `executeWith`.
    */
  private val paths: List[(String, (FlightSqlRouter, String, EffectiveSet) => Unit)] = List(
    "flightsql" -> ((r, sql, eff) =>
      run(r.execute("c-f", "probeu", probeKey, sql, effectiveSet = Some(eff)))
    ),
    "rest" -> ((r, sql, eff) =>
      run(r.execute("c-r", "probeu", probeKey, sql, effectiveSet = Some(eff), source = "rest"))
    ),
    "mcp" -> ((r, sql, eff) =>
      run(r.execute("c-m", "probeu", probeKey, sql, effectiveSet = Some(eff), source = "mcp"))
    ),
    "native front door" -> ((r, sql, eff) => frontDoor(r, sql, eff))
  )

  private def run(io: IO[Either[RouterFailure, QueryResult]]): Unit =
    io.unsafeRunSync() match
      case Right(qr) => qr.close()
      case Left(f)   => fail(s"statement refused: $f")

  private def frontDoor(router: FlightSqlRouter, sql: String, eff: EffectiveSet): Unit =
    val send: FlightSqlRouter.NodeSend[org.apache.arrow.vector.ipc.ArrowReader] =
      (node, wrappedSql, prelude, recordLoad, principal) =>
        router.adapter
          .send(node, wrappedSql, principal, None, recordLoad = recordLoad, stampPrelude = prelude)
          .map(NodeOutcome.fromQuackResponse)
    router
      .executeWith(
        "c-q",
        "probeu",
        probeKey,
        sql,
        Some(eff),
        send,
        source = "quack",
        purpose = ai.starlake.quack.edge.authz.DecidePurpose.Live
      )
      .unsafeRunSync() match
      case Right(r) => r.close()
      case Left(f)  => fail(s"statement refused: $f")

  private val UseTarget = """(?i)USE\s+([^;\s]+)\s*;""".r

  for (name, dd, policyCatalog, expected) <- cases; (path, send) <- paths do
    "the router" should s"apply a row policy as the node resolves, case $name, over $path" in {
      val (router, sent) = routerWith(dd)
      for (table, form) <- forms do
        withClue(s"$form name $table: ") {
          send(router, s"SELECT count(*) FROM $table", withRows(List(rowPolicyOn(policyCatalog))))
          UseTarget.findFirstMatchIn(sent()).map(_.group(1)) shouldBe Some(s"$Alias.main")
          filtered(sent()) shouldBe expected
        }
    }

    it should s"apply a column mask as the node resolves, case $name, over $path" in {
      val (router, sent) = routerWith(dd)
      for (table, form) <- forms do
        withClue(s"$form name $table: ") {
          send(router, s"SELECT f FROM $table", withMasks(List(maskOn(policyCatalog))))
          UseTarget.findFirstMatchIn(sent()).map(_.group(1)) shouldBe Some(s"$Alias.main")
          masked(sent()) shouldBe expected
        }
    }
