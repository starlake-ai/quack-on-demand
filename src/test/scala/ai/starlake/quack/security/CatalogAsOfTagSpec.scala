// src/test/scala/ai/starlake/quack/security/CatalogAsOfTagSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.model.SnapshotTag
import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry
}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** `asOfTag` resolution on the catalog table endpoint (EPIC P2 / Spec 06).
  *
  * GET /api/catalog/tenant/{t}/database/{db}/schemas/{s}/tables/{tbl} accepts `asOfTag=<name>`
  * alongside the existing `asOf=<id>`: the tag resolves to its snapshot id and reuses the AS OF
  * read path. Contract pinned here: tag resolves and the reader sees the tag's snapshot id; unknown
  * tag 404; both params 400; dangling tag (snapshot vacuumed) 404 through the table-not-found arm;
  * no params still reads latest.
  */
class CatalogAsOfTagSpec extends AnyFlatSpec with Matchers:

  private val Tenant   = SecurityFixtures.TenantId
  private val TenantDb = SecurityFixtures.TenantDbName

  private val LiveSnapshot     = 42L
  private val VacuumedSnapshot = 43L

  private class Stub:
    var seenAsOf: Option[Option[Long]] = None // None = getTable never called

    private val region = CatalogTableEntry("tpch1", "region", 5L, 1, None)
    private val detail = CatalogTableDetailResponse(
      region,
      List(CatalogColumnEntry(0, "r_regionkey", "INTEGER", false, false)),
      List(CatalogDataFileEntry("s3://lake/tpch1/region.parquet", 2048L, 5L, 1L))
    )

    val reader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
      override def getTable(schema: String, table: String, asOf: Option[Long] = None) =
        seenAsOf = Some(asOf)
        // The vacuumed snapshot no longer resolves: dangling tag -> None -> 404.
        if schema == "tpch1" && table == "region" && !asOf.contains(VacuumedSnapshot) then
          Some(detail)
        else None

  private def get(client: HttpClient, url: String): HttpResponse[String] =
    client.send(
      HttpRequest.newBuilder(URI.create(url)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def withHarness(test: (ManagerServerHarness.Harness, Stub) => Unit): Unit =
    val fix  = SecurityFixtures.freshStore()
    val stub = new Stub
    fix.store.createSnapshotTag(SnapshotTag("stag-v1", Tenant, TenantDb, "v1", LiveSnapshot))
    fix.store.createSnapshotTag(
      SnapshotTag("stag-dead", Tenant, TenantDb, "dead", VacuumedSnapshot)
    )
    val h = ManagerServerHarness.boot(fix.store, catalogReader = Some((_, _) => stub.reader))
    try test(h, stub)
    finally h.shutdown()

  private def tableUrl(h: ManagerServerHarness.Harness, params: String): String =
    s"${h.baseUrl}/api/catalog/tenant/$Tenant/database/$TenantDb/schemas/tpch1/tables/region$params"

  "the catalog table endpoint" should "resolve asOfTag to the tag's snapshot id" in withHarness {
    (h, stub) =>
      val resp = get(h.httpClient, tableUrl(h, "?asOfTag=v1"))
      withClue(s"body: ${resp.body()}")(resp.statusCode() shouldBe 200)
      stub.seenAsOf shouldBe Some(Some(LiveSnapshot))
  }

  it should "404 an unknown tag without touching the reader" in withHarness { (h, stub) =>
    val resp = get(h.httpClient, tableUrl(h, "?asOfTag=nope"))
    withClue(s"body: ${resp.body()}")(resp.statusCode() shouldBe 404)
    resp.body() should include("tag 'nope' not found")
    stub.seenAsOf shouldBe None
  }

  it should "400 when both asOf and asOfTag are supplied" in withHarness { (h, stub) =>
    val resp = get(h.httpClient, tableUrl(h, s"?asOf=$LiveSnapshot&asOfTag=v1"))
    withClue(s"body: ${resp.body()}")(resp.statusCode() shouldBe 400)
    stub.seenAsOf shouldBe None
  }

  it should "404 a dangling tag through the table-not-found arm" in withHarness { (h, stub) =>
    val resp = get(h.httpClient, tableUrl(h, "?asOfTag=dead"))
    withClue(s"body: ${resp.body()}")(resp.statusCode() shouldBe 404)
    resp.body() should include(s"at snapshot $VacuumedSnapshot")
    stub.seenAsOf shouldBe Some(Some(VacuumedSnapshot))
  }

  it should "still read latest when neither param is supplied" in withHarness { (h, stub) =>
    val resp = get(h.httpClient, tableUrl(h, ""))
    withClue(s"body: ${resp.body()}")(resp.statusCode() shouldBe 200)
    stub.seenAsOf shouldBe Some(None)
  }

  it should "keep the numeric asOf path intact" in withHarness { (h, stub) =>
    val resp = get(h.httpClient, tableUrl(h, s"?asOf=$LiveSnapshot"))
    withClue(s"body: ${resp.body()}")(resp.statusCode() shouldBe 200)
    stub.seenAsOf shouldBe Some(Some(LiveSnapshot))
  }
