package ai.starlake.quack.ondemand.demo

/** The `qod demo` startup banner. Scaled to the minimal demo's story: row + column security on a
  * governed catalog, connecting as the seeded analyst.
  */
object DemoBanner:

  def render(restPort: Int, flightPort: Int, dataPath: String, rows: String): String =
    s"""|
        |QoD - demo mode  (no TLS, open REST, ephemeral catalog, demo credentials)
        |
        |  DuckLake:   $dataPath (tenant acme, $rows TPC-H rows)
        |  Flight SQL: grpc://localhost:$flightPort   (TLS off)
        |  Admin UI:   http://localhost:$restPort
        |
        |  Connect as the analyst (row + column security applied):
        |
        |  from adbc_driver_flightsql import dbapi
        |  conn = dbapi.connect("grpc://localhost:$flightPort",
        |                       db_kwargs={"username": "alice", "password": "demo-alice"})
        |
        |  Try: SELECT c_name, c_phone, c_mktsegment FROM acme_tpch.tpch1.customer LIMIT 5
        |       -- c_phone comes back masked ('***'); only BUILDING-segment rows appear
        |       -- reconnect as acme-admin / demo-acme-admin and run it again: full data
        |       -- then SELECT from a table alice has no grant on: denied
        |
        |  Ctrl-C stops the demo and deletes $dataPath's parent (ephemeral).
        |""".stripMargin
