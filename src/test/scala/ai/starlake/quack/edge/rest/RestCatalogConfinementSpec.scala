package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.sql.PostgresAclValidator
import ai.starlake.quack.ondemand.state.RolePermission
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, SQLException}
import scala.util.Try

/** Checks that the REST edge's fully qualified `"<catalog>"."<schema>"."<name>"` keeps a statement
  * inside the tenant catalog, for every kind, including `memory` whose session catalog is DuckDB's
  * built-in `memory`.
  *
  * Two tiers. The policy tier runs the edge's schema probe through the router with the real
  * PostgresAclValidator (with and without `filteredMetadata`) for a principal holding a schema-wide
  * grant, and checks which three-part names reach the node. The engine tier asks in-process DuckDB
  * (core only) how a three-part name resolves when the object is missing from the tenant catalog
  * but a same-named object exists elsewhere (the temp catalog, another attached catalog).
  *
  * What they establish:
  *   - Every three-part name naming another catalog (`system`, `temp`, `memory` on a non-memory
  *     node, a sibling tenant-db) is denied by the validator under a schema-wide grant.
  *   - A system name qualified with the TENANT catalog (`"<cat>"."information_schema"."tables"`,
  *     `"<cat>"."pg_catalog".x`, `"<cat>"."main"."duckdb_settings"`) is admitted by the validator
  *     under `<cat>.*.*` (partly refused by the metadata filter when it is on), and DuckDB then
  *     refuses to resolve it: a three-part name never falls back to another catalog. The edge also
  *     404s those path schemas.
  *   - A TWO-part name does fall back: `"main"."shadow"` reads `temp.main.shadow` when the tenant
  *     catalog has none, which is why the edge must always qualify with the catalog.
  *   - On the `memory` kind a wildcard-catalog grant (`*.*.*`) covers nothing, because the
  *     validator's tenant catalog set holds tenant-db names and the session catalog is `memory`;
  *     only an explicit `memory.*.*` grant admits. Fail-closed.
  */
class RestCatalogConfinementSpec extends AnyFlatSpec with Matchers:

  import ai.starlake.quack.edge.policy.PolicyPipelineFixture.*

  /** What `BootFactories` wires as the validator's tenant catalog set: the tenant-db NAMES. */
  private def tenantDbName(kc: KindCase) = s"acme_${kc.suffix}"

  private def pipeline(kc: KindCase, filteredMetadata: Boolean) =
    harness(
      kc,
      validator = new PostgresAclValidator(
        tenantCatalogs = _ => Set(tenantDbName(kc)),
        filteredMetadata = filteredMetadata
      ),
      metadataFilter = new MetadataFilterRewriter(enabled = filteredMetadata)
    )

  private def schemaWide(catalog: String) = RolePermission("p-1", "r-1", catalog, "*", "*", "RO")

  private def probe(cat: String, sch: String, name: String) =
    s"""SELECT * FROM "$cat"."$sch"."$name" LIMIT 0"""

  /** Three-part names outside the tenant catalog, per kind. */
  private def foreign(kc: KindCase): List[(String, String, String)] =
    List(
      ("system", "main", "duckdb_settings"),
      ("system", "main", "duckdb_tables"),
      ("system", "information_schema", "tables"),
      ("system", "pg_catalog", "pg_class"),
      ("temp", "main", "shadow"),
      ("acme_other", "main", "customer")
    ) ++ (if kc.catalog == "memory" then Nil else List(("memory", "main", "orders")))

  /** System objects spelled INSIDE the tenant catalog. */
  private def systemInTenant(kc: KindCase): List[(String, String, String)] =
    List(
      (kc.catalog, "information_schema", "tables"),
      (kc.catalog, "pg_catalog", "pg_class"),
      (kc.catalog, "main", "duckdb_tables"),
      (kc.catalog, "main", "duckdb_settings")
    )

  /** The explicit schema-wide grants to test per kind (a wildcard catalog only where it applies).
    */
  private def grantsFor(kc: KindCase): List[RolePermission] =
    if kc.catalog == "memory" then List(schemaWide("memory"))
    else List(schemaWide(kc.catalog), schemaWide("*"))

  "a schema-wide grant" should "admit the tenant catalog's own table through its three-part name" in {
    for kc <- AllKinds; fm <- List(false, true); g <- grantsFor(kc) do
      pipeline(kc, fm).run(
        probe(kc.catalog, "main", "customer"),
        effWith(permissions = List(g))
      ) match
        case Right(sent) => sent should include(probe(kc.catalog, "main", "customer"))
        case Left(f)     => fail(s"${kc.kind} fm=$fm grant=${g.catalogName}: $f")
  }

  it should "never admit a three-part name of another catalog, for every kind" in {
    for kc <- AllKinds; fm <- List(false, true); g <- grantsFor(kc); (c, s, n) <- foreign(kc) do
      withClue(s"${kc.kind} fm=$fm grant=${g.catalogName}.*.* $c.$s.$n: ") {
        pipeline(kc, fm).run(probe(c, s, n), effWith(permissions = List(g))) match
          case Left(_: RouterFailure.AccessDenied) => succeed
          case other                               => fail(s"expected a denial, got $other")
      }
  }

  it should "admit system names qualified with the tenant catalog only as far as the validator goes (the engine refuses them below)" in {
    // Pinned so a validator change is visible either way: under <cat>.*.* with filteredMetadata
    // off every one of these reaches the node, where DuckDB fails to resolve it, except
    // duckdb_settings, which the node-state screen reserves to superusers.
    for kc <- AllKinds; (c, s, n) <- systemInTenant(kc) do
      pipeline(kc, filteredMetadata = false)
        .run(probe(c, s, n), effWith(permissions = List(schemaWide(kc.catalog)))) match
        case Left(_: RouterFailure.AccessDenied) if n == "duckdb_settings" => succeed
        case Right(sent) if n != "duckdb_settings" => sent should include(probe(c, s, n))
        case other => fail(s"${kc.kind} $c.$s.$n: unexpected $other")
  }

  "the memory kind" should "admit nothing under a wildcard-catalog grant (the tenant catalog set holds tenant-db names)" in {
    val kc = Memory
    for fm <- List(false, true) do
      pipeline(kc, fm).run(
        probe("memory", "main", "customer"),
        effWith(permissions = List(schemaWide("*")))
      ) match
        case Left(_: RouterFailure.AccessDenied) => succeed
        case other                               => fail(s"fm=$fm: expected a denial, got $other")
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
