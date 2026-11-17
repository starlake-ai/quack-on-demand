package ai.starlake.quack.mcp

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.{RestCaller, SearchHandlers}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.LiveTable
import ai.starlake.quack.ondemand.search.{SearchCatalog, SearchIndexService}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, SearchIndexStore}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The search tools hold a token to its databases axis, as REST does (security I1), refuse k1, b
  * and options, and drop by index id.
  */
class McpSearchToolsSpec extends AnyFlatSpec with Matchers:

  private val catalog: SearchCatalog = new SearchCatalog:
    def liveTableColumns(td: TenantDb, schema: String, table: String) =
      IO.pure(
        Option.when(schema == "main" && table == "pages")(
          LiveTable(41L, "main", "pages", List(("title", 2L, "varchar")))
        )
      )
    def liveTableName(td: TenantDb, tableId: Long) = IO.pure(Some(("main", "pages")))

  private final class Fixture(r: TokenRestriction):
    val store = new InMemoryControlPlaneStore()
    val rows  = List("lake", "logs").map(n =>
      TenantDb(s"td-$n", "acme", n, TenantDbKind.DuckLake, Map.empty, s"s3://l/$n/")
    )
    val pat = PatPrincipal(
      RbacUser("u-alice", Some("acme"), "alice", "admin"),
      "pat-1",
      SessionScope(superuser = false, manageableTenants = Set("acme")),
      isAdmin = true,
      r
    )
    val principal = new McpPrincipal.Pat(pat, "raw-token")
    val handlers  = new SearchHandlers(
      service = new SearchIndexService(
        tenantDbOf = (t, d) => Option.when(t == "acme")(rows.find(_.name == d)).flatten,
        tenantDbsOf = t => if t == "acme" then rows else Nil,
        metastoreOf = td => Map("dataPath" -> td.dataPath),
        catalog = catalog,
        store = SearchIndexStore.over(store)
      ),
      store = SearchIndexStore.over(store),
      poolExists = _ => false,
      aliasOf = _.name,
      executor = (_, _, _) => IO.pure(Left(RouterFailure.Unavailable("no node"))),
      audit = AuditRecorder.noop,
      callerOf = RestCaller(patOf = t => Option.when(t == "raw-token")(pat)),
      databaseRefusal = (_, r, db) =>
        Option.when(!r.allowsDatabase(db))(
          sttp.model.StatusCode.Forbidden -> ai.starlake.quack.ondemand.api.ErrorResponse(
            "token_scope",
            s"database '$db' is not permitted for this token"
          )
        )
    )
    val tools = new McpSearchTools(handlers, _ => pat.scope).tools.map(t => t.name -> t).toMap
    def run(tool: String, args: (String, Json)*): Either[String, Json] =
      tools(tool).run(principal, JsonObject(args*)).unsafeRunSync()

  private def str(s: String) = Json.fromString(s)

  private val createArgs = List(
    "table"   -> str("pages"),
    "columns" -> Json.arr(str("title"))
  )

  "a token limited to one database" should "create, list and drop only there" in {
    val f = new Fixture(TokenRestriction.Unrestricted.copy(databases = Some(Set("lake"))))
    f.run("create_search_index", (("database" -> str("lake")) :: createArgs)*).isRight shouldBe true
    List(
      f.run("create_search_index", (("database" -> str("logs")) :: createArgs)*),
      f.run("list_search_indexes", "database" -> str("logs")),
      f.run("drop_search_index", "database"   -> str("logs"), "table" -> str("pages"))
    ).foreach { r =>
      r.left.toOption.getOrElse(fail(s"expected a refusal, got $r")) should include("token_scope")
    }
    f.store.listSearchIndexes(Set("td-logs")) shouldBe Nil
    // Every database of the tenant: only the one the token allows (the handler filters).
    f.store.insertSearchIndex(
      f.store
        .listAllSearchIndexes()
        .head
        .copy(
          indexId = java.util.UUID.randomUUID(),
          tenantDbId = "td-logs"
        )
    ) shouldBe true
    val listed = f.run("list_search_indexes").toOption.get
    listed.hcursor
      .downField("indexes")
      .focus
      .flatMap(_.asArray)
      .map(
        _.map(
          _.hcursor.get[String]("database").toOption.get
        ).toList
      ) shouldBe Some(List("lake"))
  }

  "create_search_index" should "refuse k1, b or options" in
    List(
      "options" -> Json.obj("k1" -> Json.fromDoubleOrNull(1.2)),
      "k1"      -> Json.fromInt(1),
      "b"       -> Json.fromInt(0)
    )
      .foreach { extra =>
        val f = new Fixture(TokenRestriction.Unrestricted)
        val r = f.run("create_search_index", (("database" -> str("lake")) :: extra :: createArgs)*)
        r.left.toOption.getOrElse(fail(s"expected a refusal, got $r")) should include(
          "invalid_search_options"
        )
      }

  "drop_search_index" should "drop by index id" in {
    val f = new Fixture(TokenRestriction.Unrestricted)
    f.run("create_search_index", (("database" -> str("lake")) :: createArgs)*).isRight shouldBe true
    val id = f.store.listAllSearchIndexes().head.indexId.toString
    f.run("drop_search_index", "database" -> str("lake"), "index_id" -> str(id)) shouldBe
      Right(Json.obj("deleted" -> Json.True))
    f.store.listAllSearchIndexes() shouldBe Nil
  }
