package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.TestArrow
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** System-catalog reads on an `opa` tenant through the mounted metadata filter, over the real
  * FlightSQL wire: the gateway asks OPA nothing per statement (the node decides, spec decision 29)
  * and the filter narrows the listing on its way to the node.
  */
class OpaMetadataGateSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  private def withBoot[A](
      body: (FlightEdgeHarness.Harness, ConcurrentLinkedQueue[String]) => A
  ): A =
    val fix  = seed(s"http://localhost:${wm.port()}")
    val seen = new ConcurrentLinkedQueue[String]()
    val opa  = authorizer(ttlSec = 0)
    val h    = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        opa = Some(opa),
        nodeQuery = sql =>
          seen.add(sql)
          TestArrow.okResponse()
        ,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        spawnNodes = true,
        protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
      )
    )
    try body(h, seen)
    finally h.shutdown()

  "an opa tenant with the metadata filter mounted" should
    "forward a metadata read narrowed by the filter, asking OPA nothing per statement" in:
      wm.resetAll()
      stubConnect(wm, allow)
      stubStatement(wm, deny("the gateway must not ask"))
      withBoot { (h, seen) =>
        query(h, bob, "SELECT * FROM information_schema.tables")
        wm.verify(0, statementCalls)
        val forwarded = seen.asScala.toList.filter(_.contains("information_schema"))
        forwarded should not be empty
        // The filter replaced the bare reference with a derived table narrowed to the system
        // rows (bob holds no grant), pinned to the system catalog.
        forwarded.foreach { sql =>
          sql should include(
            "FROM information_schema.tables WHERE (table_catalog = 'system' AND " +
              "table_schema IN ('information_schema', 'pg_catalog'))"
          )
        }
      }
