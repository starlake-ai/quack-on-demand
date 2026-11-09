package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.ondemand.state.RolePermission
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, SQLException}
import scala.util.Try

/** Checks that the REST edge's fully qualified `"<catalog>"."<schema>"."<name>"` keeps a statement
  * inside the tenant catalog, for every kind, including `memory` whose session catalog is DuckDB's
  * built-in `memory`.
  *
  * Two tiers. The gateway tier runs the edge's schema probe through the router (metadata filter
  * mounted, as in production) and checks that the three-part name reaches the node as written:
  * whether the principal may read it is qod-node's decision, which `RestEdgeEndToEndSpec` exercises
  * on live nodes. The engine tier asks in-process DuckDB (core only) how a three-part name resolves
  * when the object is missing from the tenant catalog but a same-named object exists elsewhere (the
  * temp catalog, another attached catalog).
  *
  * What they establish:
  *   - The tenant catalog's own table reaches the node under exactly the name the edge wrote.
  *   - A three-part name never falls back to another catalog: a system name qualified with the
  *     TENANT catalog (`"<cat>"."information_schema"."tables"`) does not resolve, and the edge also
  *     404s those path schemas.
  *   - A TWO-part name does fall back: `"main"."shadow"` reads `temp.main.shadow` when the tenant
  *     catalog has none, which is why the edge must always qualify with the catalog.
  */
class RestCatalogConfinementSpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.edge.policy.PolicyPipelineFixture.*

  private def pipeline(kc: KindCase) =
    harness(kc, metadataFilter = new MetadataFilterRewriter(enabled = true))

  private def schemaWide(catalog: String) = RolePermission("p-1", "r-1", catalog, "*", "*", "RO")

  private def probe(cat: String, sch: String, name: String) =
    s"""SELECT * FROM "$cat"."$sch"."$name" LIMIT 0"""

  "the edge's probe" should "reach the node with the tenant catalog's three-part name intact" in {
    for kc <- AllKinds do
      pipeline(kc).run(
        probe(kc.catalog, "main", "customer"),
        effWith(permissions = List(schemaWide(kc.catalog)))
      ) match
        case Right(sent) => sent should include(probe(kc.catalog, "main", "customer"))
        case Left(f)     => fail(s"${kc.kind}: $f")
  }

  // ---- engine tier: in-process DuckDB name resolution ----

  /** The tenant catalog holds `present`; the temp catalog holds `shadow`; another catalog (and, for
    * non-memory kinds, the built-in `memory`) holds `orders`. The session is `USE`d into the tenant
    * catalog exactly as the router's prelude does.
    */
  private def withNode(kc: KindCase)(f: Connection => Unit): Unit =
    withDuckDb(kc.catalog) { conn =>
      exec(conn, "ATTACH ':memory:' AS acme_other")
      exec(conn, "CREATE TABLE acme_other.main.orders AS SELECT 'acme_other' AS src")
      if kc.catalog != "memory" then
        exec(conn, "CREATE TABLE memory.main.orders AS SELECT 'memory' AS src")
      exec(conn, "CREATE TEMP TABLE shadow AS SELECT 'temp' AS src")
      exec(conn, s"""CREATE TABLE "${kc.catalog}"."main"."present" AS SELECT 'tenant' AS src""")
      exec(conn, s"""USE "${kc.catalog}"."main"""")
      f(conn)
    }

  private def resolves(conn: Connection, sql: String): Either[String, List[List[String]]] =
    Try(query(conn, sql)).toEither.left.map {
      case e: SQLException => e.getMessage
      case e               => throw e
    }

  "a three-part name" should "resolve only inside the named catalog, never falling back to temp, system or another catalog" in {
    for kc <- AllKinds do
      withNode(kc) { conn =>
        resolves(conn, s"""SELECT src FROM "${kc.catalog}"."main"."present"""") shouldBe
          Right(List(List("tenant")))
        val missing =
          List("shadow", "orders", "duckdb_tables", "duckdb_settings").map(n =>
            s"""SELECT * FROM "${kc.catalog}"."main"."$n""""
          ) ++ List(
            s"""SELECT * FROM "${kc.catalog}"."information_schema"."tables"""",
            s"""SELECT * FROM "${kc.catalog}"."pg_catalog"."pg_class""""
          )
        missing.foreach { sql =>
          withClue(s"${kc.kind}: $sql -> ") {
            resolves(conn, sql) match
              case Left(msg) => msg should include("Catalog Error")
              case Right(rs) => fail(s"resolved outside the tenant catalog: $rs")
          }
        }
      }
  }

  "a two-part name" should "fall back to the temp catalog when the tenant catalog lacks the object (why the edge qualifies fully)" in {
    for kc <- AllKinds do
      withNode(kc) { conn =>
        resolves(conn, """SELECT src FROM "main"."shadow"""") shouldBe Right(List(List("temp")))
        resolves(conn, """SELECT src FROM "shadow"""") shouldBe Right(List(List("temp")))
        // The same object through the three-part form stays out of reach.
        resolves(conn, s"""SELECT src FROM "${kc.catalog}"."main"."shadow"""").isLeft shouldBe true
      }
  }
