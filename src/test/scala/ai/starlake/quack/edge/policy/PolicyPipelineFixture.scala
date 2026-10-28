package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.{FlightSqlRouter, RouterFailure, SessionRegistry}
import ai.starlake.quack.edge.adapter.{
  NodeLoadTracker,
  QuackHttpAdapter,
  QuackHttpClient,
  QuackResponse,
  TestArrow
}
import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.sql.StatementValidator
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacUser,
  RoleColumnPolicy,
  RolePermission,
  RoleRowPolicy
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import java.sql.{Connection, DriverManager}
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.util.Using

/** Shared fixture for specs that drive a statement through the whole policy pipeline
  * (FlightSqlRouter: ACL validator, column and row policy rewriters, metadata filter): a router
  * over a one-node pool of a chosen tenant-db kind, whose node call only captures the fully
  * rewritten, `USE`-prefixed SQL, plus an in-process DuckDB (core, no extensions) to execute that
  * captured text against seeded tables.
  */
object PolicyPipelineFixture:

  /** One kind under test: its wire kind and the DuckDB session catalog the router resolves. */
  final case class KindCase(kind: TenantDbKind, suffix: String, catalog: String)

  val DuckLake: KindCase   = KindCase(TenantDbKind.DuckLake, "lake", "acme_lake")
  val DuckDbFile: KindCase = KindCase(TenantDbKind.DuckDbFile, "file", "acme_file")

  /** The `memory` kind's session catalog is DuckDB's built-in `memory`. */
  val Memory: KindCase = KindCase(TenantDbKind.InMemory, "mem", "memory")

  val AllKinds: List[KindCase] = List(DuckLake, DuckDbFile, Memory)

  val tenantUser: RbacUser = RbacUser("u-1", Some("acme"), "alice", "user")

  def effWith(
      permissions: List[RolePermission] = Nil,
      columnPolicies: List[RoleColumnPolicy] = Nil,
      rowPolicies: List[RoleRowPolicy] = Nil
  ): EffectiveSet =
    EffectiveSet(tenantUser, Nil, Nil, permissions, Nil, columnPolicies, rowPolicies)

  /** A router plus the last SQL its node call received. */
  final class Harness(
      val router: FlightSqlRouter,
      val poolKey: PoolKey,
      captured: () => Option[String],
      val sup: PoolSupervisor
  ):
    /** Run `sql` through the whole pipeline; Right(sent SQL) or the router's refusal. */
    def run(sql: String, eff: EffectiveSet): Either[RouterFailure, String] =
      router
        .execute(
          s"policy-pipeline-${System.nanoTime()}",
          "alice",
          poolKey,
          sql,
          effectiveSet = Some(eff)
        )
        .unsafeRunSync()
        .map { qr =>
          qr.close()
          captured().getOrElse(throw new IllegalStateException("node call not captured"))
        }

  /** Build a [[Harness]] for `kc`. The DuckLake kind skips Postgres provisioning through the
    * supervisor's own seams (`NoopDbAdmin`, a no-op initializer), so no catalog is touched.
    */
  def harness(
      kc: KindCase,
      columnCatalog: ColumnCatalog = new ColumnCatalog.MapCatalog(Map.empty),
      validator: StatementValidator = StatementValidator.allowAll,
      metadataFilter: MetadataFilterRewriter = new MetadataFilterRewriter(enabled = false),
      /** What the node answers to the statement it received; by default one row of `SELECT 1`. */
      respond: String => QuackResponse = _ => TestArrow.okResponse(),
      /** The control plane under the supervisor; a spec seeds principals into it. */
      store: InMemoryControlPlaneStore = new InMemoryControlPlaneStore(),
      /** Mounts the production protected-write guard over `columnCatalog` (CLS and RLS on). */
      guarded: Boolean = false
  ): Harness =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          26000 + n.size,
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
    val sup     = new PoolSupervisor(
      backend,
      tracker,
      store,
      duckLakeInitializer = (_, _) => ()
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val metastore = kc.kind match
      case TenantDbKind.DuckLake =>
        Map(
          "pgHost"     -> "localhost",
          "pgPort"     -> "5432",
          "pgUser"     -> "postgres",
          "pgPassword" -> "unused",
          "schemaName" -> "main"
        )
      case TenantDbKind.DuckDbFile => Map("dbName" -> kc.catalog, "schemaName" -> "main")
      case TenantDbKind.InMemory   => Map.empty[String, String]
    val dataPath = kc.kind match
      case TenantDbKind.InMemory => ""
      case _                     => s"/tmp/qod-policy-pipeline/${kc.catalog}"
    sup
      .createTenantDb("acme", kc.suffix, kc.kind, metastore, dataPath)
      .unsafeRunSync()
      .fold(e => throw new IllegalStateException(s"createTenantDb(${kc.kind}): $e"), identity)
    val key = PoolKey("acme", s"acme_${kc.suffix}", "sales")
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()

    @volatile var last: Option[String] = None
    val client                         = new QuackHttpClient(TestArrow.sharedAllocator):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        last = Some(sql)
        IO(respond(sql))
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      columnPolicyRewriter = new ColumnPolicyRewriter(columnCatalog, enabled = true),
      metadataFilterRewriter = metadataFilter,
      protectedWriteGuard =
        if guarded then new ProtectedWriteGuard(columnCatalog, clsEnabled = true, rlsEnabled = true)
        else ProtectedWriteGuard.disabled
    )
    new Harness(router, key, () => last, sup)

  // ---- in-process DuckDB (core only) ----

  /** A fresh in-memory DuckDB with the tenant catalog attached under `catalog` (a no-op for the
    * built-in `memory`), closed after `f`.
    */
  def withDuckDb[A](catalog: String)(f: Connection => A): A =
    Class.forName("org.duckdb.DuckDBDriver")
    Using.resource(DriverManager.getConnection("jdbc:duckdb:")) { conn =>
      if catalog != "memory" then exec(conn, s"ATTACH ':memory:' AS \"$catalog\"")
      f(conn)
    }

  def exec(conn: Connection, sql: String): Unit =
    Using.resource(conn.createStatement())(_.execute(sql)): Unit

  /** Execute a captured node statement: the router's `USE <db>.<schema>; ` prelude is run on its
    * own, then the rows of the final statement are returned as strings (NULL as null).
    */
  def rowsOf(conn: Connection, sentSql: String): List[List[String]] =
    val UsePrefix = """(?s)^\s*(USE\s+[^;]+);\s*(.*)$""".r
    val body      = sentSql match
      case UsePrefix(use, rest) => exec(conn, use); rest
      case other                => other
    query(conn, body)

  def query(conn: Connection, sql: String): List[List[String]] =
    Using.resource(conn.createStatement()) { st =>
      Using.resource(st.executeQuery(sql)) { rs =>
        val n   = rs.getMetaData.getColumnCount
        val out = ListBuffer.empty[List[String]]
        while rs.next() do out += (1 to n).map(i => rs.getString(i)).toList
        out.toList
      }
    }
