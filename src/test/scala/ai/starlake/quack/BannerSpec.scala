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

  it should "create the control-plane database when the server is up but the database is missing" in {
    import ai.starlake.quack.ondemand.state.testkit.TestPostgres
    TestPostgres.ensureReachable()
    val name = s"qod_cpb_banner_test_${System.nanoTime()}"
    val m    = Map(
      "pgHost"     -> TestPostgres.pgHost,
      "pgPort"     -> TestPostgres.pgPort.toString,
      "pgUser"     -> TestPostgres.pgUser,
      "pgPassword" -> TestPostgres.pgPass,
      "dbName"     -> name
    )
    try
      Banner.postgresPreflight(m) shouldBe Right(())
      // The database must now exist: a second probe succeeds without creating anything.
      Banner.postgresPreflight(m) shouldBe Right(())
    finally scala.util.Try(TestPostgres.dropDatabase(name))
  }

  "startup" should "render the endpoints with TLS on, 0.0.0.0 mapped, and link the client docs" in {
    val b =
      Banner.startup(
        meta,
        "0.0.0.0",
        20900,
        "0.0.0.0",
        31338,
        tlsEnabled = true
      )
    b should include("http://localhost:20900/ui")
    b should include("SQL ACL       : ENFORCED on every node")
    (b should not).include("NODE LOCKDOWN")
    b should include("grpc+tls://localhost:31338")
    b should include("Client connection strings: https://docs.starlake.ai/qod/connecting/clients")
    (b should not).include("jdbc:arrow-flight-sql")
    (b should not).include("Arrow Flight SQL ODBC Driver")
    (b should not).include("adbc_driver_flightsql")
  }

  it should "render plain grpc when TLS is off" in {
    val b =
      Banner.startup(
        meta,
        "myhost",
        20900,
        "myhost",
        31338,
        tlsEnabled = false
      )
    b should include("grpc://myhost:31338")
    (b should not).include("QOD_ACL_ENABLED")
    b should include(
      "ACL MODE      : qod (default for tenants that set none; 0 tenant(s) in opa mode)"
    )
  }

  it should "say every node enforces, whatever the default mode and the opa tenants" in {
    for (mode, opa) <- List(("qod", 0), ("qod", 2), ("opa", 0)) do
      val b = Banner.startup(
        meta,
        "myhost",
        20900,
        "myhost",
        31338,
        tlsEnabled = false,
        aclMode = mode,
        opaTenants = opa
      )
      withClue(s"$mode/$opa ") {
        b should include(
          "SQL ACL       : ENFORCED on every node (QoD grants, or the tenant's OPA in opa mode)"
        )
        b should include(
          s"ACL MODE      : $mode (default for tenants that set none; $opa tenant(s) in opa mode)"
        )
        (b should not).include("DISABLED")
      }
  }

  it should "name opa as the default mode when QOD_ACL_MODE=opa" in {
    val b = Banner.startup(
      meta,
      "myhost",
      20900,
      "myhost",
      31338,
      tlsEnabled = false,
      aclMode = "opa",
      opaTenants = 0
    )
    b should include("SQL ACL       : ENFORCED on every node")
    b should include(
      "ACL MODE      : opa (default for tenants that set none; 0 tenant(s) in opa mode)"
    )
  }

  it should "name the qod CLI config file only when launched through the CLI" in {
    val withCli = Banner.startup(
      meta,
      "myhost",
      20900,
      "myhost",
      31338,
      tlsEnabled = false,
      cliConfigFile = Some("/home/u/.config/qod/config.toml")
    )
    withCli should include(
      "qod config    : /home/u/.config/qod/config.toml\n   control plane : "
    )
    val bare =
      Banner.startup(
        meta,
        "myhost",
        20900,
        "myhost",
        31338,
        tlsEnabled = false
      )
    (bare should not).include("qod config")
  }

  it should "list each problem under PROBLEMS and count them on the is-up line" in {
    import ai.starlake.quack.ondemand.PoolSupervisor.StartupProblem
    val problems = List(
      StartupProblem(
        "acme",
        "acme_tpch",
        StartupProblem.Kind.Blocked,
        "its dataPath /x/qod/ducklake/acme_tpch does not match",
        List(
          "restart the manager with QOD_DUCKLAKE_DATA_PATH='/Users/me/Application Support/data'",
          "pin this database"
        ),
        List("bi", "etl")
      ),
      StartupProblem(
        "globex",
        "globex_tpcds",
        StartupProblem.Kind.Federation,
        "its federation sources did not resolve (| env var not set)",
        List("fix the source"),
        List("bi")
      )
    )
    val b = Banner.startup(
      meta,
      "0.0.0.0",
      20900,
      "0.0.0.0",
      31338,
      tlsEnabled = true,
      problems = problems
    )
    b should include("is up, with 2 problem(s): see PROBLEMS below")
    b should include("\n PROBLEMS\n   acme/acme_tpch: BLOCKED, pools bi, etl NOT started\n")
    b should include("     why : its dataPath /x/qod/ducklake/acme_tpch does not match\n")
    // Never wrapped: the quoted path with a space copies whole.
    b should include(
      "     fix : restart the manager with QOD_DUCKLAKE_DATA_PATH='/Users/me/Application Support/data'\n"
    )
    b should include("      or : pin this database\n")
    b should include("   globex/globex_tpcds: federation aliases missing (pools bi)\n")
    // Problem text is kept verbatim, even a line that stripMargin would have eaten.
    b should include("(| env var not set)")
    b should include("Client connection strings:")
  }

  it should "print no PROBLEMS section when there is none" in {
    val b = Banner.startup(meta, "0.0.0.0", 20900, "0.0.0.0", 31338, tlsEnabled = true)
    (b should not).include("PROBLEMS")
    b should include(" is up\n")
  }
