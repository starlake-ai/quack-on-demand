package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.RoleDistribution
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the JSON wire contract of every request/response DTO whose absent optional fields must fall
  * back to the case-class defaults, plus the `protected` wire-key rename on the tag DTOs.
  *
  * The absent-field cases guard the codec-derivation strategy in [[Dtos]]: a codec that silently
  * diverges from its case class (a field the decoder never reads or the encoder never writes)
  * passes a plain round-trip test constructed with defaults, so each case here feeds a NON-default
  * value through the wire or decodes minimal JSON and asserts the defaults.
  */
class DtosWireContractSpec extends AnyFlatSpec with Matchers:

  import Dtos.given

  // ----- CreatePoolRequest: every field must survive the wire ------------

  "CreatePoolRequest" should "decode initSql from the wire" in:
    val json =
      """{"tenant":"acme","tenantDb":"acme_db","pool":"bi","size":1,
        |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1},
        |"initSql":"SET memory_limit='8GB'"}""".stripMargin
    decode[CreatePoolRequest](json).map(_.initSql) shouldBe Right(Some("SET memory_limit='8GB'"))

  it should "encode initSql to the wire" in:
    val req = CreatePoolRequest(
      tenant = "acme",
      tenantDb = "acme_db",
      pool = "bi",
      size = 1,
      roleDistribution = RoleDistribution(0, 0, 1),
      initSql = Some("SET memory_limit='8GB'")
    )
    req.asJson.hcursor.get[Option[String]]("initSql") shouldBe
      Right(Some("SET memory_limit='8GB'"))

  it should "default the optional fields when absent from the wire" in:
    val json =
      """{"tenant":"acme","tenantDb":"acme_db","pool":"bi","size":1,
        |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
    decode[CreatePoolRequest](json) shouldBe Right(
      CreatePoolRequest("acme", "acme_db", "bi", 1, RoleDistribution(0, 0, 1))
    )
    decode[CreatePoolRequest](json).map(r => (r.minNodes, r.maxNodes)) shouldBe Right((None, None))

  it should "round-trip with every non-default field populated" in:
    val req = CreatePoolRequest(
      tenant = "acme",
      tenantDb = "acme_db",
      pool = "bi",
      size = 2,
      roleDistribution = RoleDistribution(1, 0, 1),
      idleTimeoutSec = 300,
      maxConcurrentPerNode = 4,
      cohorts = List(
        PoolCohortDto(
          placement = NodePlacementDto(
            nodeSelector = Map("zone" -> "a"),
            tolerations = List(NodeTolerationDto("gpu", "Exists", None, Some("NoSchedule")))
          ),
          distribution = RoleDistribution(1, 0, 1)
        )
      ),
      disabled = true,
      initSql = Some("INSTALL httpfs"),
      cpu = "2",
      memory = "4Gi",
      podTemplateYaml = "metadata: {}",
      minNodes = Some(1),
      maxNodes = Some(4)
    )
    decode[CreatePoolRequest](req.asJson.noSpaces) shouldBe Right(req)

  // ----- SetPoolAutoscaleRequest: one-sided and absent bands ---------------

  "SetPoolAutoscaleRequest" should "default both band bounds to None when absent" in:
    decode[SetPoolAutoscaleRequest]("""{"tenant":"a","tenantDb":"d","pool":"p"}""") shouldBe
      Right(SetPoolAutoscaleRequest("a", "d", "p"))

  it should "round-trip a populated band" in:
    val req = SetPoolAutoscaleRequest("a", "d", "p", Some(1), Some(4))
    decode[SetPoolAutoscaleRequest](req.asJson.noSpaces) shouldBe Right(req)

  // ----- PoolResponse: band mirror ----------------------------------------

  // PoolResponse derives a strict codec, so the absent-field case is built by
  // dropping the two band keys from a full encoding rather than by hand-writing
  // a minimal object (every non-Option field stays required).
  "PoolResponse" should "default the band to None when the keys are absent from the wire" in:
    val json = PoolResponse("acme", "acme_db", "bi", Nil, "ready").asJson
      .mapObject(_.remove("minNodes").remove("maxNodes"))
      .noSpaces
    decode[PoolResponse](json).map(r => (r.minNodes, r.maxNodes)) shouldBe Right((None, None))

  it should "round-trip a populated band" in:
    val resp = PoolResponse(
      tenant = "acme",
      tenantDb = "acme_db",
      pool = "bi",
      nodes = Nil,
      status = "ready",
      minNodes = Some(1),
      maxNodes = Some(4)
    )
    decode[PoolResponse](resp.asJson.noSpaces) shouldBe Right(resp)

  // ----- Pool ops: force defaults to false --------------------------------

  "ScalePoolRequest" should "default force to false when absent" in:
    val json =
      """{"tenant":"a","tenantDb":"d","pool":"p","targetSize":2,
        |"roleDistribution":{"writeonly":0,"readonly":0,"dual":2}}""".stripMargin
    decode[ScalePoolRequest](json).map(_.force) shouldBe Right(false)

  "StopPoolRequest" should "default force to false when absent" in:
    decode[StopPoolRequest]("""{"tenant":"a","tenantDb":"d","pool":"p"}""") shouldBe
      Right(StopPoolRequest("a", "d", "p"))

  "DeletePoolRequest" should "default force to false when absent" in:
    decode[DeletePoolRequest]("""{"tenant":"a","tenantDb":"d","pool":"p"}""") shouldBe
      Right(DeletePoolRequest("a", "d", "p"))

  // ----- Placement DTOs ----------------------------------------------------

  "NodeTolerationDto" should "default operator/value/effect when absent" in:
    decode[NodeTolerationDto]("""{"key":"gpu"}""") shouldBe Right(NodeTolerationDto("gpu"))

  "NodePlacementDto" should "default both fields when absent" in:
    decode[NodePlacementDto]("{}") shouldBe Right(NodePlacementDto())

  "PoolCohortDto" should "default placement when absent" in:
    decode[PoolCohortDto]("""{"distribution":{"writeonly":0,"readonly":1,"dual":0}}""") shouldBe
      Right(PoolCohortDto(distribution = RoleDistribution(0, 1, 0)))

  // ----- NodeInfo ----------------------------------------------------------

  "NodeInfo" should "default all metric fields when absent" in:
    val json = """{"nodeId":"n1","role":"DUAL","host":"127.0.0.1","port":21900}"""
    decode[NodeInfo](json) shouldBe Right(NodeInfo("n1", "DUAL", "127.0.0.1", 21900))

  // ----- Tenants -----------------------------------------------------------

  "TenantRequest" should "default displayName/authProvider/authConfig when absent" in:
    decode[TenantRequest]("""{"id":"acme"}""") shouldBe Right(TenantRequest("acme"))

  "SetTenantAuthRequest" should "default authConfig when absent" in:
    decode[SetTenantAuthRequest]("""{"name":"acme","authProvider":"db"}""") shouldBe
      Right(SetTenantAuthRequest("acme", "db"))

  "TenantDbRequest" should "default every optional field when absent" in:
    decode[TenantDbRequest]("""{"tenant":"acme","name":"acme_x"}""") shouldBe
      Right(TenantDbRequest("acme", "acme_x"))
    decode[TenantDbRequest]("""{"tenant":"acme","name":"acme_x"}""").map(_.managedStorage) shouldBe
      Right(false)

  it should "round-trip managedStorage through the wire" in:
    val req = TenantDbRequest("acme", "sales", managedStorage = true)
    req.asJson.hcursor.get[Boolean]("managedStorage") shouldBe Right(true)
    decode[TenantDbRequest](req.asJson.noSpaces) shouldBe Right(req)
    decode[TenantDbRequest](
      """{"tenant":"acme","name":"sales","managedStorage":true}"""
    ).map(_.managedStorage) shouldBe Right(true)

  "TenantDbOpRequest" should "decode a body written before purgeManagedData existed" in:
    // Pins the deriveCodec -> ConfiguredCodec swap: without it the added field makes
    // every existing database/delete body fail to decode.
    decode[TenantDbOpRequest]("""{"tenant":"t","name":"n"}""") shouldBe
      Right(TenantDbOpRequest("t", "n"))
    decode[TenantDbOpRequest]("""{"tenant":"t","name":"n"}""").map(_.purgeManagedData) shouldBe
      Right(false)

  it should "round-trip purgeManagedData through the wire" in:
    val req = TenantDbOpRequest("t", "n", purgeManagedData = true)
    req.asJson.hcursor.get[Boolean]("purgeManagedData") shouldBe Right(true)
    decode[TenantDbOpRequest](req.asJson.noSpaces) shouldBe Right(req)

  // ----- Auth --------------------------------------------------------------

  "LoginResponse" should "default tenant/superuser/manageableTenants when absent" in:
    decode[LoginResponse]("""{"token":"t","username":"u"}""") shouldBe
      Right(LoginResponse("t", "u"))

  "WhoamiResponse" should "default tenant/superuser/manageableTenants when absent" in:
    decode[WhoamiResponse]("""{"username":"u","role":"admin"}""") shouldBe
      Right(WhoamiResponse("u", "admin"))

  "AuthModeResponse" should "default ssoProviderName when absent" in:
    decode[AuthModeResponse]("""{"mode":"db"}""") shouldBe Right(AuthModeResponse("db"))

  // ----- RBAC --------------------------------------------------------------

  "UserCreateRequest" should "default tenant to None and role to user when absent" in:
    decode[UserCreateRequest]("""{"username":"u","password":"p"}""") shouldBe
      Right(UserCreateRequest(None, "u", "p"))

  "RolePermissionGrantRequest" should "default catalog/schema/table to * when absent" in:
    decode[RolePermissionGrantRequest]("""{"roleId":"r1","verb":"RO"}""") shouldBe
      Right(RolePermissionGrantRequest("r1", verb = "RO"))

  "CreateColumnPolicyRequest" should "default catalog/schema/table to * when absent" in:
    val json = """{"roleId":"r1","columnName":"ssn","action":"mask"}"""
    decode[CreateColumnPolicyRequest](json) shouldBe
      Right(CreateColumnPolicyRequest("r1", "*", "*", "*", "ssn", "mask", None))

  "UpdateColumnPolicyRequest" should "default transformSql to None when absent" in:
    decode[UpdateColumnPolicyRequest]("""{"id":"p1","action":"mask"}""") shouldBe
      Right(UpdateColumnPolicyRequest("p1", "mask"))

  "CreateRowPolicyRequest" should "default catalog/schema/table to * when absent" in:
    val json = """{"roleId":"r1","predicateSql":"region = 'EU'"}"""
    decode[CreateRowPolicyRequest](json) shouldBe
      Right(CreateRowPolicyRequest("r1", "*", "*", "*", "region = 'EU'"))

  // ----- Federation --------------------------------------------------------

  "FederatedSourceCreateRequest" should "default description/disabled when absent" in:
    decode[FederatedSourceCreateRequest]("""{"alias":"pg","setupSql":"ATTACH ..."}""") shouldBe
      Right(FederatedSourceCreateRequest("pg", "ATTACH ..."))

  // ----- Maintenance -------------------------------------------------------

  "MaintenancePolicyUpsertRequest" should "default every optional knob to None when absent" in:
    val json = """{"tenant":"acme","tenantDb":"acme_db","scopeKind":"tenantdb"}"""
    decode[MaintenancePolicyUpsertRequest](json) shouldBe
      Right(MaintenancePolicyUpsertRequest("acme", "acme_db", "tenantdb"))

  "MaintenanceRunRequest" should "default scope/operations to None when absent" in:
    decode[MaintenanceRunRequest]("""{"tenant":"acme","tenantDb":"acme_db"}""") shouldBe
      Right(MaintenanceRunRequest("acme", "acme_db"))

  // ----- Tags: the wire key is `protected`, a Scala keyword ----------------

  "CatalogTagEntry" should "read and write the protected wire key" in:
    val json    = """{"name":"v1","snapshotId":3,"protected":true}"""
    val decoded = decode[CatalogTagEntry](json)
    decoded.map(_.isProtected) shouldBe Right(true)
    decoded.map(_.exists) shouldBe Right(true)
    val entry = CatalogTagEntry("v1", 3L, isProtected = true, None, None, exists = true)
    entry.asJson.hcursor.get[Boolean]("protected") shouldBe Right(true)

  "TagCreateRequest" should "default protected to false and use the protected wire key" in:
    decode[TagCreateRequest]("""{"tenant":"a","tenantDb":"d","name":"v1","snapshotId":3}""")
      .map(_.isProtected) shouldBe Right(false)
    TagCreateRequest("a", "d", "v1", 3L, isProtected = true).asJson.hcursor
      .get[Boolean]("protected") shouldBe Right(true)

  "TagProtectRequest" should "use the protected wire key both ways" in:
    decode[TagProtectRequest]("""{"tenant":"a","tenantDb":"d","name":"v1","protected":true}""")
      .map(_.isProtected) shouldBe Right(true)
    TagProtectRequest("a", "d", "v1", isProtected = true).asJson.hcursor
      .get[Boolean]("protected") shouldBe Right(true)
