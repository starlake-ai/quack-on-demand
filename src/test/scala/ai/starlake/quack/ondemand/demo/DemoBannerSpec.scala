package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DemoBannerSpec extends AnyFlatSpec with Matchers:

  "DemoBanner.render" should "name the insecure caveats, the routing headers, the RLS/CLS beat, and link the client docs" in {
    val b =
      DemoBanner.render(
        restPort = 20900,
        flightPort = 31338,
        dataPath = "/demo",
        rows = "~150K",
        adminNames = Seq("admin@localhost.local", "admin"),
        adminPasswordLabel = "admin"
      )
    b should include("self-signed TLS") // caveat: encrypted, but clients must skip verification
    b should include("ephemeral")       // caveat
    b should include(
      "Flight SQL/ODBC/ADBC/JDBC/Quack Client connection strings: " +
        "https://docs.starlake.ai/qod/connecting/clients"
    )
    b should include("-> tenant=acme")
    b should include("-> pool=bi")
    b should include("c_phone masked") // CLS beat, in the Flight SQL user table
    b should include("BUILDING")       // RLS beat
  }

  it should "print the admin UI url and every seeded credential as table rows" in {
    val b =
      DemoBanner.render(
        restPort = 20900,
        flightPort = 31338,
        dataPath = "/demo",
        rows = "~150K",
        adminNames = Seq("admin@localhost.local", "admin"),
        adminPasswordLabel = "admin"
      )
    b should include("http://localhost:20900/ui/")
    // Admin UI table: Tenant | User | Password | Access, one row per seeded identity.
    b should include regex """│ Tenant\s+│ User\s+│ Password\s+│ Access\s+│"""
    b should include regex """│ \(blank\)\s+│ root\s+│ demo-root\s+│ superuser console\s+│"""
    b should include regex """│ \(blank\)\s+│ admin\s+│ admin\s+│ superuser console\s+│"""
    b should include regex """│ acme\s+│ acme-admin\s+│ demo-acme-admin\s+│ acme-scoped view\s+│"""
    b should include regex """│ acme\s+│ alice\s+│ demo-alice\s+│ acme-scoped view\s+│"""
    // Flight SQL table: User | Password | Access | Notes.
    b should include regex """│ User\s+│ Password\s+│ Access\s+│ Notes\s+│"""
    b should include regex """│ alice\s+│ demo-alice\s+│ analyst\s+│ c_phone masked, BUILDING rows only\s+│"""
    b should include regex """│ acme-admin\s+│ demo-acme-admin\s+│ everything in acme\s+│ unmasked\s+│"""
    b should include regex """│ root\s+│ demo-root\s+│ superuser\s+│ add superuser=true\s+│"""
    b should include regex """│ admin\s+│ admin\s+│ superuser\s+│ add superuser=true\s+│"""
    // Box borders are drawn.
    b should include("┌")
    b should include("└")
  }

  it should "list every seeded admin name with the password label it is given" in {
    val b =
      DemoBanner.render(
        restPort = 20900,
        flightPort = 31338,
        dataPath = "/demo",
        rows = "~150K",
        adminNames = Seq("admin@localhost.local", "admin"),
        adminPasswordLabel = "<QOD_ADMIN_PASSWORD>"
      )
    for name <- Seq("admin@localhost.local", "admin") do
      val n = java.util.regex.Pattern.quote(name)
      b should include regex s"""│ \\(blank\\)\\s+│ $n\\s+│ <QOD_ADMIN_PASSWORD>\\s+│ superuser console\\s+│"""
      b should include regex s"""│ $n\\s+│ <QOD_ADMIN_PASSWORD>\\s+│ superuser\\s+│ add superuser=true\\s+│"""
    // The fixed demo identities keep their manifest passwords.
    b should include regex """│ \(blank\)\s+│ root\s+│ demo-root\s+│ superuser console\s+│"""
    (b should not).include(regex("""│ admin\s+│ admin\s+│"""))
  }

  it should "not print client connection strings" in {
    val b =
      DemoBanner.render(
        restPort = 20900,
        flightPort = 31338,
        dataPath = "/demo",
        rows = "~150K",
        adminNames = Seq("admin@localhost.local", "admin"),
        adminPasswordLabel = "admin"
      )
    (b should not).include("jdbc:arrow-flight-sql")
    (b should not).include("Arrow Flight SQL ODBC Driver")
    (b should not).include("dbapi.connect")
  }

  it should "print the storage notes under the data path, and nothing extra without them" in {
    def render(notes: Seq[String]) = DemoBanner.render(
      restPort = 20900,
      flightPort = 31338,
      dataPath = "s3://b12/qod-demo-1a2b3c4d",
      rows = "~150K",
      adminNames = Seq("admin"),
      adminPasswordLabel = "admin",
      storageNotes = notes
    )
    render(Seq("note one", "note two")) should include(
      "DuckLake: s3://b12/qod-demo-1a2b3c4d (tenant acme, ~150K TPC-H rows)\n  note one\n  note two\n"
    )
    render(Nil) should include("TPC-H rows)\n\n  Admin UI:")
  }

  "DemoBanner.awaitPort" should "return true once the port accepts and false on timeout" in {
    val srv = new java.net.ServerSocket(0)
    try
      DemoBanner.awaitPort("127.0.0.1", srv.getLocalPort, timeoutMs = 5000) shouldBe true
    finally srv.close()
    // srv is closed: nothing listens there anymore.
    DemoBanner.awaitPort("127.0.0.1", srv.getLocalPort, timeoutMs = 300) shouldBe false
  }
