package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BannerSpec extends AnyFlatSpec with Matchers:

  private val meta = Map(
    "pgHost"     -> "localhost",
    "pgPort"     -> "5432",
    "pgUser"     -> "postgres",
    "pgPassword" -> "azizam",
    "dbName"     -> "qod"
  )

  "jdbcControlPlaneUrl" should "render host, port, and database" in {
    Banner.jdbcControlPlaneUrl(meta) shouldBe "jdbc:postgresql://localhost:5432/qod"
  }

  "postgresPreflight" should "refuse clearly when nothing listens on the port" in {
    val dead = meta.updated("pgPort", "1") // reserved port, nothing listens
    val out  = Banner.postgresPreflight(dead, timeoutSec = 2)
    out.isLeft shouldBe true
    val msg = out.left.getOrElse("")
    msg should include("Postgres is NOT reachable")
    msg should include("jdbc:postgresql://localhost:1/qod")
    msg should include("pgHost/pgPort/pgUser/pgPassword/dbName")
  }

  "startup" should "render copy-pasteable strings with TLS on and 0.0.0.0 mapped" in {
    val b = Banner.startup(meta, "0.0.0.0", 20900, "0.0.0.0", 31338, tlsEnabled = true)
    b should include("http://localhost:20900/ui")
    b should include("grpc+tls://localhost:31338")
    b should include(
      "jdbc:arrow-flight-sql://localhost:31338/?tenant=<tenant>&pool=<pool>&user=<user>" +
        "&useEncryption=true&disableCertificateVerification=true"
    )
    b should include("Arrow Flight SQL ODBC Driver")
    b should include("adbc_driver_flightsql")
  }

  it should "render plain grpc and useEncryption=false when TLS is off" in {
    val b = Banner.startup(meta, "myhost", 20900, "myhost", 31338, tlsEnabled = false)
    b should include("grpc://myhost:31338")
    b should include("&useEncryption=false")
    (b should not).include("DisableCertificateVerification")
  }
