package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DemoBannerSpec extends AnyFlatSpec with Matchers:

  "DemoBanner.render" should "name the insecure caveats, a demo user snippet, and the RLS/CLS beat" in {
    val b =
      DemoBanner.render(restPort = 20900, flightPort = 31338, dataPath = "/demo", rows = "~150K")
    b should include("self-signed TLS") // caveat: encrypted, but clients must skip verification
    b should include("ephemeral")       // caveat
    b should include("grpc+tls://localhost:31338")
    b should include("username") // runnable connect snippet uses a seeded demo user
    // Self-signed cert: without skip-verify the ADBC snippet dies on cert validation.
    b should include("adbc.flight.sql.client_option.tls_skip_verify")
    // The edge requires tenant scope headers on Basic auth; without these three
    // the snippet dies with UNAUTHENTICATED 'tenant header required'.
    b should include("adbc.flight.sql.rpc.call_header.tenant")
    b should include("adbc.flight.sql.rpc.call_header.pool")
    b should include("adbc.flight.sql.rpc.call_header.db")
    b should include("c_phone")  // CLS beat
    b should include("masked")   // CLS beat
    b should include("BUILDING") // RLS beat
    b should include("denied")   // denial beat
  }

  it should "print the admin UI url and every seeded credential" in {
    val b =
      DemoBanner.render(restPort = 20900, flightPort = 31338, dataPath = "/demo", rows = "~150K")
    b should include("http://localhost:20900/ui/")
    // The four identities seeded by bootstrap-demo-minimal.yaml.
    b should include("root / demo-root")
    b should include("admin / admin")
    b should include("alice / demo-alice")
    b should include("acme-admin / demo-acme-admin")
  }

  it should "print copy-pastable JDBC, ADBC, and ODBC configurations" in {
    val b =
      DemoBanner.render(restPort = 20900, flightPort = 31338, dataPath = "/demo", rows = "~150K")
    b should include(
      "jdbc:arrow-flight-sql://localhost:31338?useEncryption=true" +
        "&disableCertificateVerification=true&user=alice&password=demo-alice&tenant=acme&pool=bi"
    )
    b should include("Driver={Arrow Flight SQL ODBC Driver};HOST=localhost;PORT=31338")
    b should include("RPCCallHeaders=tenant=acme;pool=bi")
    b should include("dbapi.connect(\"grpc+tls://localhost:31338\"")
  }

  "DemoBanner.awaitPort" should "return true once the port accepts and false on timeout" in {
    val srv = new java.net.ServerSocket(0)
    try
      DemoBanner.awaitPort("127.0.0.1", srv.getLocalPort, timeoutMs = 5000) shouldBe true
    finally srv.close()
    // srv is closed: nothing listens there anymore.
    DemoBanner.awaitPort("127.0.0.1", srv.getLocalPort, timeoutMs = 300) shouldBe false
  }
