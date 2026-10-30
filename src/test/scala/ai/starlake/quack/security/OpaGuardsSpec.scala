package ai.starlake.quack.security

import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import ai.starlake.quack.ondemand.state.{RbacRole, RoleColumnPolicy}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.arrow.flight.{FlightRuntimeException, FlightStatusCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** OPA decides access only (on the node, spec decision 29): an OPA that allows everything must not
  * switch off the gateway's policy steps. Over the real FlightSQL wire, `bob` (opa tenant, no
  * grant) holds a QoD role `masked` that carries a REAL column policy masking `orders.email`.
  */
class OpaGuardsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val MaskedRoleId = "r-masked01"

  /** One catalog shared by the SELECT-path rewriter and the write-path guard, as in production. */
  private val catalog =
    new ColumnCatalog.MapCatalog(Map(("memory", "main", "orders") -> List("id", "email")))

  private def withBoot[A](body: FlightEdgeHarness.Harness => A): A =
    val fix = seed(s"http://localhost:${wm.port()}")
    val s   = fix.store
    s.upsertRole(
      RbacRole(id = MaskedRoleId, tenantId = SecurityFixtures.TenantId, name = "masked")
    )
    s.insertColumnPolicy(
      RoleColumnPolicy(
        id = "cp-orders-email",
        roleId = MaskedRoleId,
        catalogName = "*",
        schemaName = "main",
        tableName = "orders",
        columnName = "email",
        action = RoleColumnPolicy.ActionMask,
        transformSql = Some("'***'")
      )
    )
    s.addUserRole(fix.bobUserId, MaskedRoleId)
    val opa = authorizer()
    val h   = FlightEdgeHarness.boot(
      s,
      wiring = FlightEdgeHarness.RouterWiring(
        opa = Some(opa),
        nodeQuery = duckNode(OrdersSetup),
        columnPolicyRewriter = new ColumnPolicyRewriter(catalog, enabled = true),
        protectedWriteGuard =
          new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true),
        spawnNodes = true,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
      )
    )
    try body(h)
    finally h.shutdown()

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  private def allowEverything(): Unit =
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)

  "an allow-everything OPA" should "not lift column masking: a role's mask policy masks the column" in:
    allowEverything()
    withBoot { h =>
      val rows = query(h, bob, "SELECT id, email FROM orders")
      rows.map(_("id")) shouldBe List("1", "2")
      rows.map(_("email")) shouldBe List("***", "***")
      wm.verify(0, statementCalls)
    }

  it should "not lift the protected-write guard: a CTAS laundering the masked column is refused" in:
    allowEverything()
    withBoot { h =>
      val e = intercept[FlightRuntimeException](
        query(h, bob, "CREATE TABLE main.scratch AS SELECT email FROM orders")
      )
      e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      e.getMessage should include("cannot be read into a write statement")
      wm.verify(0, statementCalls)
    }
