package ai.starlake.quack.security

import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** #242: the harness once picked a free port, released it and bound it later, so a port another
  * process took in between failed the spec (`Failed to bind to address`). The edge now binds port 0
  * and the harness reads back the port the OS assigned at bind time.
  */
class FlightEdgeHarnessPortSpec extends AnyFlatSpec with Matchers:

  private def boot() = FlightEdgeHarness.boot(
    SecurityFixtures.freshStore().store,
    wiring = FlightEdgeHarness.RouterWiring(
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
      protectedWriteGuard = ProtectedWriteGuard.disabled
    )
  )

  "FlightEdgeHarness" should "listen on the port the OS assigned when the edge bound" in {
    val h = boot()
    try
      h.port should be > 0
      // The port is the server's own: a connection to it is accepted.
      val socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress, h.port)
      socket.close()
    finally h.shutdown()
  }

  it should "give two harnesses booted side by side a port each" in {
    val a = boot()
    try
      val b = boot()
      try a.port should not be b.port
      finally b.shutdown()
    finally a.shutdown()
  }
