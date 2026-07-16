package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DemoBannerSpec extends AnyFlatSpec with Matchers:

  "DemoBanner.render" should "name the insecure caveats, a demo user snippet, and the RLS/CLS beat" in {
    val b =
      DemoBanner.render(restPort = 20900, flightPort = 31338, dataPath = "/demo", rows = "~150K")
    b should include("demo mode")
    b should include("no TLS")    // caveat
    b should include("ephemeral") // caveat
    b should include("grpc://localhost:31338")
    b should include("http://localhost:20900")
    b should include("username") // runnable connect snippet uses a seeded demo user
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
