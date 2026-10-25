package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CreatePoolRequest,
  PoolHandlers,
  TenantDbHandlers,
  TenantDbRequest,
  TenantHandlers,
  TenantRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.runtime.testkit.{LogCapture, StubQuackBackend}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** `ServeProvisionHook`, the add-only boot provisioning `qod serve` hands its manager: it creates
  * what the spec names and is missing, through the REST create path, as the manager itself; it
  * never updates or deletes; a refusal stops it and lands in the outcome file and the log; it never
  * fails the boot.
  */
class ServeProvisionHookSpec extends AnyFlatSpec with Matchers:

  private val TenantReq = TenantRequest(id = "acme", displayName = "acme")
  private val Database  = TenantDbRequest(tenant = "acme", name = "shop", kind = "memory")
  private val PoolReq   = CreatePoolRequest(
    tenant = "acme",
    tenantDb = "acme_shop",
    pool = "main",
    size = 1,
    roleDistribution = RoleDistribution(0, 0, 1)
  )
  private val HookLogger = ServeProvisionHook.getClass.getName

  /** A stand-in credential. A spec's object-store keys travel to the hook, and nothing the hook
    * writes may carry one. Checked with `contains(...) shouldBe false`, so a failure never prints
    * it.
    */
  private val Marker = "sekrit-0123456789"

  /** A booted supervisor (restore() done) with the three REST handlers Main hands the hook, all
    * auditing into one recording store; the spec file lives in a fresh directory.
    */
  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    val telemetry     = new RecordingTelemetryStore
    val audit         = new AuditRecorder(telemetry, _ => None)
    val tenants       = new TenantHandlers(sup, audit = audit)
    val tenantDbs     = new TenantDbHandlers(sup, audit = audit, requireEncryption = false)
    val pools         = new PoolHandlers(sup, new NodeLoadTracker, audit = audit)
    val dir: Path     = Files.createTempDirectory("qod-serve-provision")
    val spec: Path    = dir.resolve("serve-provision.json")
    val outcome: Path = dir.resolve(ServeProvisionHook.OutcomeFileName)

    def write(database: TenantDbRequest = Database): Unit =
      Files.writeString(
        spec,
        Json
          .obj(
            "tenant"   -> TenantReq.asJson,
            "database" -> database.asJson,
            "pool"     -> PoolReq.asJson
          )
          .noSpaces
      )

    /** The hook as Main runs it, for the spec at `path`; Left(the error) if it failed the boot. */
    def run(path: Path = spec): Either[Throwable, Unit] =
      ServeProvisionHook
        .run(Map(ServeProvisionHook.EnvKey -> path.toString).get, sup, tenants, tenantDbs, pools)
        .attempt
        .unsafeRunSync()

    def outcomeJson: Json = parse(Files.readString(outcome)).fold(e => throw e, identity)

    def files: Set[String] =
      Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toSet)

  "ServeProvisionHook" should "create the tenant, database and pool it is missing, as the manager" in {
    val f = new Fixture
    f.write()
    val (result, logs) = LogCapture.logged(HookLogger)(f.run())
    result shouldBe Right(())
    f.sup.getTenant("acme").map(_.displayName) shouldBe Some("acme")
    f.sup.findTenantDb("acme", "acme_shop").map(_.kind) shouldBe Some(TenantDbKind.InMemory)
    f.sup.get(PoolKey("acme", "acme_shop", "main")).map(_.distribution) shouldBe
      Some(RoleDistribution(0, 0, 1))
    Files.exists(f.outcome) shouldBe true
    f.outcomeJson shouldBe Json.obj(
      "status"  -> "ok".asJson,
      "created" -> List("tenant acme", "database acme/acme_shop", "pool acme/acme_shop/main").asJson
    )
    // The REST create path's own audit rows, attributed to the manager itself.
    f.telemetry.events.toList.map(e => (e.action, e.outcome, e.actor, e.actorRealm)) shouldBe List(
      ("tenant.create", "ok", "system", "system"),
      ("database.create", "ok", "system", "system"),
      ("pool.create", "ok", "system", "system")
    )
    logs.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage) shouldBe List(
      "serve provision: created tenant acme",
      "serve provision: created database acme/acme_shop",
      "serve provision: created pool acme/acme_shop/main"
    )
    // Temporary file renamed into place: nothing else is left beside the spec.
    f.files shouldBe Set("serve-provision.json", ServeProvisionHook.OutcomeFileName)
  }

  it should "leave an existing tenant, database and pool exactly as they are" in {
    val f = new Fixture
    f.sup.createTenant(Tenant("acme", displayName = "Acme Corp")).unsafeRunSync().isRight shouldBe
      true
    f.sup
      .createTenantDb(
        "acme",
        "shop",
        TenantDbKind.InMemory,
        Map.empty,
        "",
        defaultSchema = Some("sales")
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.sup
      .createPool(PoolKey("acme", "acme_shop", "main"), RoleDistribution(0, 0, 2))
      .unsafeRunSync()
    f.write() // asks for display name "acme", no default schema, one node
    f.run() shouldBe Right(())
    f.sup.getTenant("acme").map(_.displayName) shouldBe Some("Acme Corp")
    f.sup.findTenantDb("acme", "acme_shop").flatMap(_.defaultSchema) shouldBe Some("sales")
    f.sup.get(PoolKey("acme", "acme_shop", "main")).map(_.distribution) shouldBe
      Some(RoleDistribution(0, 0, 2))
    Files.exists(f.outcome) shouldBe true
    f.outcomeJson shouldBe Json.obj("status" -> "ok".asJson, "created" -> List.empty[String].asJson)
    f.telemetry.events shouldBe empty
  }

  it should "stop at a refused step and record it in the outcome file and the log, never failing" in {
    val f       = new Fixture
    val refused = Database.copy(
      metastore = Map("BASH_ENV" -> "/tmp/x"),
      objectStore = Map("s3_secret_access_key" -> Marker)
    )
    f.write(database = refused)
    val (result, logs) = LogCapture.logged(HookLogger)(f.run())
    result shouldBe Right(())
    // The refusal POST /api/database/create answers for the same body, word for word.
    val rest = new Fixture
    rest.sup.createTenant(Tenant("acme")).unsafeRunSync()
    val message = rest.tenantDbs
      .createTenantDb(refused, None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .map(_._2.message)
      .getOrElse(fail("database/create accepted a metastore key it must refuse"))
    Files.exists(f.outcome) shouldBe true
    f.outcomeJson shouldBe Json.obj(
      "status" -> "failed".asJson,
      "step"   -> "database".asJson,
      "reason" -> message.asJson
    )
    f.sup.get(PoolKey("acme", "acme_shop", "main")) shouldBe None
    logs.filter(_.getLevel == Level.ERROR).map(_.getFormattedMessage) shouldBe
      List(s"serve provision: the database step was refused: $message")
    // The body's credential reaches no outcome, log line or audit row.
    Files.readString(f.outcome).contains(Marker) shouldBe false
    logs.exists(_.getFormattedMessage.contains(Marker)) shouldBe false
    f.telemetry.events.exists(_.detail.values.exists(_.contains(Marker))) shouldBe false
  }

  it should "refuse a database that exists with other data, and leave it as it is" in {
    // Same kind and location, other views.
    val f = new Fixture
    f.sup.createTenant(Tenant("acme")).unsafeRunSync()
    f.sup
      .createTenantDb(
        "acme",
        "shop",
        TenantDbKind.InMemory,
        Map.empty,
        "",
        initSql = "CREATE VIEW v AS SELECT 1 AS a"
      )
      .unsafeRunSync()
    f.write() // the spec's database has no initSql: other views
    f.run() shouldBe Right(())
    Files.exists(f.outcome) shouldBe true
    f.outcomeJson shouldBe Json.obj(
      "status" -> "failed".asJson,
      "step"   -> "database".asJson,
      "reason" -> ("database 'acme_shop' already exists with the same dataPath and kind, but " +
        "its views point at different data (initSql differs); serve it under another name: " +
        "qod serve <target> --name <other>").asJson
    )
    f.sup.findTenantDb("acme", "acme_shop").map(_.initSql) shouldBe
      Some("CREATE VIEW v AS SELECT 1 AS a")
    f.sup.get(PoolKey("acme", "acme_shop", "main")) shouldBe None
    // Another location: the reason names both.
    val g = new Fixture
    g.sup.createTenant(Tenant("acme")).unsafeRunSync()
    g.sup
      .createTenantDb("acme", "shop", TenantDbKind.InMemory, Map.empty, "/data/old")
      .unsafeRunSync()
    g.write()
    g.run() shouldBe Right(())
    g.outcomeJson.hcursor.get[String]("reason") shouldBe Right(
      "database 'acme_shop' already serves kind='memory' dataPath='/data/old', not " +
        "kind='memory' dataPath=''; serve it under another name: qod serve <target> --name <other>"
    )
    g.sup.findTenantDb("acme", "acme_shop").map(_.dataPath) shouldBe Some("/data/old")
  }

  it should "refuse a pool name that already serves another database of the tenant" in {
    val f = new Fixture
    f.sup.createTenant(Tenant("acme")).unsafeRunSync()
    f.sup.createTenantDb("acme", "other", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    f.sup
      .createPool(PoolKey("acme", "acme_other", "main"), RoleDistribution(0, 0, 1))
      .unsafeRunSync()
    f.write() // asks for pool main on acme_shop
    f.run() shouldBe Right(())
    Files.exists(f.outcome) shouldBe true
    f.outcomeJson shouldBe Json.obj(
      "status" -> "failed".asJson,
      "step"   -> "pool".asJson,
      "reason" -> ("pool 'main' already serves database 'acme_other', not 'acme_shop'; serve it " +
        "under another name: qod serve <target> --pool <other>").asJson
    )
    f.sup.get(PoolKey("acme", "acme_other", "main")) should not be empty
    f.sup.get(PoolKey("acme", "acme_shop", "main")) shouldBe None
  }

  it should "report a spec it cannot read at the spec step without echoing it, never failing" in {
    val f = new Fixture
    Files.writeString(f.spec, """{"tenant": {"id": "acme"}""")
    f.run() shouldBe Right(())
    Files.exists(f.outcome) shouldBe true
    f.outcomeJson shouldBe Json.obj(
      "status" -> "failed".asJson,
      "step"   -> "spec".asJson,
      "reason" -> s"cannot read the provisioning spec ${f.spec}: not valid JSON".asJson
    )
    // A credential of the wrong type is named by its path, never quoted (circe's own message
    // would quote the value).
    Files.writeString(
      f.spec,
      Json
        .obj(
          "tenant"   -> TenantReq.asJson,
          "database" -> Database.asJson.deepMerge(
            Json.obj(
              "objectStore" -> Json.obj("s3_secret_access_key" -> Json.fromLong(9876543210L))
            )
          ),
          "pool" -> PoolReq.asJson
        )
        .noSpaces
    )
    val (_, logs) = LogCapture.logged(HookLogger)(f.run())
    Files.readString(f.outcome).contains("9876543210") shouldBe false
    logs.exists(_.getFormattedMessage.contains("9876543210")) shouldBe false
    f.outcomeJson.hcursor.get[String]("reason") shouldBe Right(
      s"cannot read the provisioning spec ${f.spec}: .database.objectStore.s3_secret_access_key " +
        "is missing or has the wrong type"
    )
    Files.writeString(f.spec, """{"tenant": {"id": "acme"}}""")
    f.run() shouldBe Right(())
    f.outcomeJson.hcursor.get[String]("reason") shouldBe
      Right(
        s"cannot read the provisioning spec ${f.spec}: .database is missing or has the wrong type"
      )
    f.sup.listTenants() shouldBe empty
    // No outcome can be written beside a spec in a missing directory: logged, boot goes on.
    val gone              = f.dir.resolve("gone").resolve("serve-provision.json")
    val (result, goneLog) = LogCapture.logged(HookLogger)(f.run(gone))
    result shouldBe Right(())
    goneLog.filter(_.getLevel == Level.ERROR).map(_.getFormattedMessage) should contain(
      s"serve provision: could not finish for $gone: java.nio.file.NoSuchFileException"
    )
  }

  it should "never print a spec whole: its database body may carry credentials" in {
    val spec = ServeProvisionHook.Spec(
      TenantReq,
      Database.copy(objectStore = Map("s3_secret_access_key" -> Marker)),
      PoolReq
    )
    spec.toString.contains(Marker) shouldBe false
  }
