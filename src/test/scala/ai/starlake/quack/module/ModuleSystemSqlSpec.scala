package ai.starlake.quack.module

import ai.starlake.quack.boot.RoutedExecutor
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.admin.AdminSqlParser
import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.ExecCaller
import ai.starlake.quack.ondemand.module.ModuleSystemSql
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The module SPI's system SQL capability ([[ai.starlake.quack.spi.SystemSql]], built by
  * [[ModuleSystemSql]]) over the real [[RoutedExecutor]], with a recording router leg in place of
  * `FlightSqlRouter.execute`: what it records is what the router would receive. The statement runs
  * as `ExecCaller.system`, labelled with the module's name, and every call leaves one audit row
  * naming the module, the pool, the outcome and the statement. `ModuleSystemSqlE2ESpec` runs the
  * same capability through Main's wiring against a real node.
  */
class ModuleSystemSqlSpec extends AnyFlatSpec with Matchers:

  private val Module = "seed-test"

  /** An Arrow stream of `batches` empty batches; `failOnRead` breaks it on the first read. */
  private final class Reader(batches: Int, failOnRead: Boolean = false)
      extends ArrowReader(new RootAllocator()):
    var reads                             = 0
    override def loadNextBatch(): Boolean =
      if failOnRead then throw new java.io.IOException("stream broke mid-result")
      else
        reads += 1
        reads <= batches
    override def bytesRead(): Long                 = 0L
    override protected def closeReadSource(): Unit = ()
    override protected def readSchema(): Schema    = new Schema(java.util.List.of())

  private final class Fixture(answer: () => IO[Either[RouterFailure, QueryResult]]):
    val store = new InMemoryControlPlaneStore()
    val sup   =
      new PoolSupervisor(StubQuackBackend.noop(countingPorts = true), new NodeLoadTracker, store)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "default",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-module-system-sql-spec"
      )
      .unsafeRunSync()
    val key = PoolKey("acme", "acme_default", "default")
    sup.createPool(key, RoleDistribution(1, 0, 0)).unsafeRunSync()
    // The tenant's own admin, as a signup enrolls it: no pool grant in this fixture.
    store.upsertUserIdentity(
      RbacUser(id = "u-enrollee", tenant = Some("acme"), username = "enrollee", kind = "admin")
    )

    var routed: List[(ExecCaller, PoolKey, String, Option[EffectiveSet], Boolean)] = Nil
    val telemetry = new RecordingTelemetryStore
    val audit     = new AuditRecorder(telemetry, _ => None)
    // recordExecution = true, as Main binds the capability.
    val executor = RoutedExecutor(
      sup,
      StatementClassifier.default,
      (caller, poolKey, sql, eff, rec) =>
        IO { routed = routed :+ ((caller, poolKey, sql, eff, rec)) } *> answer()
    )(recordExecution = true)
    val systemSql = ModuleSystemSql(Module, executor, audit)

    def run(sql: String): Either[String, Unit] = systemSql.execute(key, sql).unsafeRunSync()
    def auditRows                              =
      telemetry.events.toList.filter(_.action == AuditActions.ModuleSql)

  private def ok(reader: => ArrowReader = new Reader(1), onClose: () => Unit = () => ()) =
    () => IO(Right(QueryResult(reader, onClose, "node-1", 3L)))

  "a module's system SQL" should "run a statement the tenant's own admin is refused, as the system caller" in {
    val f = new Fixture(ok())
    // The enrollee through the same executor: refused at the handshake gate, nothing routed.
    val asEnrollee = f
      .executor(
        ExecCaller.unrestricted("seed-as-enrollee", "enrollee", superuserAdmissible = false),
        f.key,
        "CALL dbgen(sf => 1, catalog => 'memory')"
      )
      .unsafeRunSync()
    asEnrollee.left.toOption.map(_.getClass.getSimpleName) shouldBe Some("AccessDenied")
    f.routed shouldBe Nil

    f.run("CALL dbgen(sf => 1, catalog => 'memory')") shouldBe Right(())
    f.routed should have size 1
    val (caller, poolKey, sql, eff, rec) = f.routed.head
    caller.system shouldBe true
    poolKey shouldBe f.key
    sql shouldBe "CALL dbgen(sf => 1, catalog => 'memory')"
    rec shouldBe true
    // The synthetic system set, run on the node as the system principal.
    eff.map(_.system) shouldBe Some(true)
    NodePrincipal.of(eff) shouldBe NodePrincipal.System
  }

  it should "label the caller with the module's name, in history and on the session" in {
    val f = new Fixture(ok())
    f.run("SELECT 1")
    val caller = f.routed.head._1
    caller.identity shouldBe s"module:$Module"
    caller.connectionId shouldBe s"module-$Module-acme-acme_default-default"
    caller.patId shouldBe None
  }

  it should "drain and close the result" in {
    var closed = false
    val reader = new Reader(3)
    val f      = new Fixture(ok(reader, () => closed = true))
    f.run("CREATE OR REPLACE TABLE region AS SELECT 1") shouldBe Right(())
    reader.reads shouldBe 4
    closed shouldBe true
  }

  it should "audit the call with the module, the pool, the outcome and the statement" in {
    val f = new Fixture(ok())
    f.run("INSTALL tpch")
    val rows = f.auditRows
    rows should have size 1
    val e = rows.head
    e.family shouldBe "control-plane"
    e.actor shouldBe s"module:$Module"
    e.actorRealm shouldBe "system"
    e.tenant shouldBe Some("acme")
    e.target shouldBe Some("acme_default/default")
    e.outcome shouldBe "ok"
    e.detail shouldBe Map("module" -> Module, "sql" -> "INSTALL tpch")
  }

  it should "answer a router failure as its error text, audited as an error" in {
    val f = new Fixture(() => IO.pure(Left(RouterFailure.Unavailable("no node is serving"))))
    f.run("SELECT 1") shouldBe Left("no node is serving")
    val e = f.auditRows.head
    e.outcome shouldBe "error"
    e.detail("reason") shouldBe "no node is serving"
  }

  it should "answer a node refusal as its error text, audited as denied" in {
    val f = new Fixture(() => IO.pure(Left(RouterFailure.AccessDenied("QoD: refused"))))
    f.run("SELECT 1") shouldBe Left("QoD: refused")
    f.auditRows.head.outcome shouldBe "denied"
  }

  it should "answer a thrown failure and a broken result stream as errors, never throw" in {
    val thrown = new Fixture(() => IO.raiseError(new RuntimeException("node call blew up")))
    thrown.run("SELECT 1") shouldBe Left("node call blew up")
    thrown.auditRows.head.outcome shouldBe "error"

    var closed = false
    val broken = new Fixture(ok(new Reader(1, failOnRead = true), () => closed = true))
    broken.run("SELECT 1") shouldBe Left("stream broke mid-result")
    closed shouldBe true
    broken.auditRows.head.outcome shouldBe "error"
  }

  it should "redact a claim-shaped statement and its error in the audit row, as history does" in {
    val f = new Fixture(() =>
      IO.pure(Left(RouterFailure.Unavailable("LINE 1: CREATE USER x PASSWORD 'topsecret'")))
    )
    val sql = "CREATE USER x PASSWORD 'topsecret'"
    AdminSqlParser.claims(sql) shouldBe true
    f.run(sql).isLeft shouldBe true
    val e = f.auditRows.head
    e.detail("sql") shouldBe AdminSqlParser.RedactedPlaceholder
    e.detail("reason") shouldBe AdminSqlParser.RedactedPlaceholder
  }

  it should "cap the statement and the reason it audits at 500 characters" in {
    val long = "SELECT " + ("1," * 400) + "1"
    val f    = new Fixture(() => IO.pure(Left(RouterFailure.Unavailable("e" * 900))))
    f.run(long)
    val e = f.auditRows.head
    e.detail("sql") shouldBe long.take(500)
    e.detail("reason") shouldBe "e" * 500
  }

  "ManagerContext" should "not be buildable without the system SQL capability" in {
    // The positive control: the same construction with the capability compiles, so the negative
    // case below fails for the missing field and nothing else.
    """
      ai.starlake.quack.spi.ManagerContext(
        supervisor = null,
        users = null,
        controlPlaneDs = null,
        rawConfig = com.typesafe.config.ConfigFactory.empty(),
        audit = null,
        singleton = null,
        scopeOf = ai.starlake.quack.ondemand.auth.SessionScope.resolver(_ => None),
        sessionOf = _ => None,
        systemSql = (_, _) => cats.effect.IO.pure(Right(()))
      )
    """ should compile
    """
      ai.starlake.quack.spi.ManagerContext(
        supervisor = null,
        users = null,
        controlPlaneDs = null,
        rawConfig = com.typesafe.config.ConfigFactory.empty(),
        audit = null,
        singleton = null,
        scopeOf = ai.starlake.quack.ondemand.auth.SessionScope.resolver(_ => None),
        sessionOf = _ => None
      )
    """ shouldNot typeCheck
  }
