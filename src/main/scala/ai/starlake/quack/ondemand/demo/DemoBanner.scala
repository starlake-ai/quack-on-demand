package ai.starlake.quack.ondemand.demo

/** The `qod demo` startup banner. Scaled to the minimal demo's story: row + column security on a
  * governed catalog, with copy-pastable admin UI and JDBC / ADBC / ODBC configurations for every
  * seeded credential (bootstrap-demo-minimal.yaml).
  */
object DemoBanner:

  def render(restPort: Int, flightPort: Int, dataPath: String, rows: String): String =
    s"""|
        |===================================================================
        | QoD demo ready  (self-signed TLS, open REST, ephemeral catalog)
        |===================================================================
        |
        |  DuckLake: $dataPath (tenant acme, $rows TPC-H rows)
        |
        |  Admin UI: http://localhost:$restPort/ui/
        |    tenant blank + root / demo-root               superuser console
        |    tenant blank + admin / admin                  superuser console
        |    tenant acme  + acme-admin / demo-acme-admin   acme-scoped view
        |    tenant acme  + alice / demo-alice             acme-scoped view
        |
        |  Flight SQL: grpc+tls://localhost:$flightPort  (tenant=acme, pool=bi)
        |    alice / demo-alice             analyst: c_phone masked, BUILDING rows only
        |    acme-admin / demo-acme-admin   everything in acme, unmasked
        |    root / demo-root               superuser (add superuser=true)
        |    admin / admin                  superuser (add superuser=true)
        |
        |  JDBC:
        |    jdbc:arrow-flight-sql://localhost:$flightPort?useEncryption=true&disableCertificateVerification=true&user=alice&password=demo-alice&tenant=acme&pool=bi
        |
        |  ADBC (Python):
        |    from adbc_driver_flightsql import dbapi
        |    conn = dbapi.connect("grpc+tls://localhost:$flightPort",
        |                         db_kwargs={"username": "alice", "password": "demo-alice",
        |                                    "adbc.flight.sql.client_option.tls_skip_verify": "true",
        |                                    "adbc.flight.sql.rpc.call_header.tenant": "acme",
        |                                    "adbc.flight.sql.rpc.call_header.pool": "bi",
        |                                    "adbc.flight.sql.rpc.call_header.db": "acme_tpch"})
        |
        |  ODBC (any Arrow Flight SQL ODBC driver):
        |    Driver={Arrow Flight SQL ODBC Driver};HOST=localhost;PORT=$flightPort;useEncryption=true;disableCertificateVerification=true;UID=alice;PWD=demo-alice;RPCCallHeaders=tenant=acme;pool=bi
        |
        |  Try: SELECT c_name, c_phone, c_mktsegment FROM acme_tpch.tpch1.customer LIMIT 5
        |       -- as alice: c_phone comes back masked ('***'); only BUILDING-segment rows appear
        |       -- as acme-admin: full data; a table alice has no grant on: denied
        |
        |  Ctrl-C stops the demo and deletes $dataPath's parent (ephemeral).
        |""".stripMargin

  /** Poll `host:port` with short connect attempts until it accepts a TCP connection or `timeoutMs`
    * elapses. Used to hold the banner back until the manager is actually up, so it prints last and
    * is not buried under boot logs.
    */
  def awaitPort(host: String, port: Int, timeoutMs: Long): Boolean =
    val deadline = System.nanoTime() + timeoutMs * 1000000L
    var up       = false
    while !up && System.nanoTime() < deadline do
      val socket = new java.net.Socket()
      try
        socket.connect(new java.net.InetSocketAddress(host, port), 250)
        up = true
      catch case _: Exception => Thread.sleep(200)
      finally
        try socket.close()
        catch case _: Exception => ()
    up
