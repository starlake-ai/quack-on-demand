// src/test/scala/ai/starlake/quack/security/HistoryAfterCursorSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditEvent, StatementEvent}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The ascending `after` cursor and `patId` on `GET /api/audit/list` and
  * `GET /api/history/statements`.
  */
class HistoryAfterCursorSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val Acme   = SecurityFixtures.TenantId
  private val Globex = SecurityFixtures.GlobexTenantId
  private val BaseTs = Instant.parse("2026-07-01T10:00:00Z")

  private def audit(n: Int, tenant: String, patId: Option[String] = None) = AuditEvent(
    ts = BaseTs.plusSeconds(n.toLong),
    family = "control-plane",
    actor = "alice",
    actorRealm = "tenant",
    tenant = Some(tenant),
    action = "role.create",
    target = Some(s"r-$n"),
    outcome = "ok",
    origin = "rest",
    detail = Map("n" -> n.toString),
    patId = patId
  )

  private def statement(n: Int, tenant: String, patId: Option[String] = None) = StatementEvent(
    ts = BaseTs.plusSeconds(n.toLong),
    username = "alice",
    tenant = tenant,
    pool = "sales",
    nodeId = "n1",
    sql = s"SELECT $n",
    durationMs = 1L,
    prepareMs = None,
    status = "ok",
    error = None,
    patId = patId
  )

  private def boot() =
    val ts  = new RecordingTelemetryStore
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    val h = ManagerServerHarness.boot(fix.store, telemetryStore = ts)
    (h, ts)

  private def rootToken(h: ManagerServerHarness.Harness) =
    h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)

  private def rows(body: String, field: String): List[Json] =
    parse(body).toOption
      .flatMap(_.hcursor.downField(field).as[List[Json]].toOption)
      .getOrElse(Nil)

  private def ids(body: String, field: String): List[Long] =
    rows(body, field).flatMap(_.hcursor.get[String]("id").toOption).map(_.toLong)

  private def nextAfter(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[Option[String]]("nextAfter").toOption.flatten)

  /** Walks a route forward from `after=0`, one page at a time, until a page comes back empty. */
  private def walk(fetch: Long => String, field: String): List[Long] =
    val seen   = List.newBuilder[Long]
    var cursor = 0L
    var more   = true
    var pages  = 0
    while more && pages < 20 do
      pages += 1
      val body = fetch(cursor)
      val page = ids(body, field)
      if page.isEmpty then more = false
      else
        seen ++= page
        cursor = nextAfter(body).map(_.toLong).getOrElse(page.last)
    seen.result()

  "GET /api/audit/list with after" should "return every row once, oldest first, across pages" in {
    val (h, ts) = boot()
    try
      ts.appendAudit((1 to 5).toList.map(audit(_, Acme)))
      val token = rootToken(h)
      val all   = ids(
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?limit=500", Some(token)).body(),
        "events"
      ).sorted
      all should have size 5
      val walked = walk(
        c => get(h.httpClient, s"${h.baseUrl}/api/audit/list?limit=2&after=$c", Some(token)).body(),
        "events"
      )
      walked shouldBe all
    finally h.shutdown()
  }

  it should "exclude the cursor row itself" in {
    val (h, ts) = boot()
    try
      ts.appendAudit((1 to 3).toList.map(audit(_, Acme)))
      val token  = rootToken(h)
      val sorted = ids(
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?limit=500", Some(token)).body(),
        "events"
      ).sorted
      val body =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?after=${sorted.head}", Some(token)).body()
      ids(body, "events") shouldBe sorted.tail
    finally h.shutdown()
  }

  it should "refuse before and after together" in {
    val (h, _) = boot()
    try
      val resp =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?after=1&before=9", Some(rootToken(h)))
      resp.statusCode() shouldBe 400
      errorCode(resp.body()) shouldBe Some("invalid_cursor")
    finally h.shutdown()
  }

  it should "refuse a malformed after" in {
    val (h, _) = boot()
    try
      val resp =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?after=abc", Some(rootToken(h)))
      resp.statusCode() shouldBe 400
      errorCode(resp.body()) shouldBe Some("invalid_cursor")
    finally h.shutdown()
  }

  it should "keep a tenant admin inside their tenants" in {
    val (h, ts) = boot()
    try
      ts.appendAudit(List(audit(1, Acme), audit(2, Globex), audit(3, Acme)))
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(Acme)
      )
      val body = get(h.httpClient, s"${h.baseUrl}/api/audit/list?after=0", Some(token)).body()
      rows(body, "events").flatMap(_.hcursor.get[String]("tenant").toOption) shouldBe
        List(Acme, Acme)
    finally h.shutdown()
  }

  "GET /api/audit/list" should "return patId when a token wrote the row, and omit it otherwise" in {
    val (h, ts) = boot()
    try
      ts.appendAudit(List(audit(1, Acme, Some("pat-1")), audit(2, Acme)))
      val body =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?after=0", Some(rootToken(h))).body()
      val patIds = rows(body, "events").map(_.hcursor.get[Option[String]]("patId").toOption.flatten)
      patIds shouldBe List(Some("pat-1"), None)
    finally h.shutdown()
  }

  it should "still page newest first with before, and set no nextAfter" in {
    val (h, ts) = boot()
    try
      ts.appendAudit((1 to 3).toList.map(audit(_, Acme)))
      val body =
        get(h.httpClient, s"${h.baseUrl}/api/audit/list?limit=2", Some(rootToken(h))).body()
      val page = ids(body, "events")
      page shouldBe page.sorted.reverse
      nextAfter(body) shouldBe None
    finally h.shutdown()
  }

  "GET /api/history/statements with after" should "return every row once, oldest first" in {
    val (h, ts) = boot()
    try
      ts.appendStatements((1 to 5).toList.map(statement(_, Acme)))
      val token = rootToken(h)
      val all   = ids(
        get(h.httpClient, s"${h.baseUrl}/api/history/statements?limit=500", Some(token)).body(),
        "statements"
      ).sorted
      all should have size 5
      val walked = walk(
        c =>
          get(h.httpClient, s"${h.baseUrl}/api/history/statements?limit=2&after=$c", Some(token))
            .body(),
        "statements"
      )
      walked shouldBe all
    finally h.shutdown()
  }

  it should "refuse before and after together" in {
    val (h, _) = boot()
    try
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements?after=1&before=9",
        Some(rootToken(h))
      )
      resp.statusCode() shouldBe 400
      errorCode(resp.body()) shouldBe Some("invalid_cursor")
    finally h.shutdown()
  }

  it should "return patId when a token ran the statement" in {
    val (h, ts) = boot()
    try
      ts.appendStatements(List(statement(1, Acme, Some("pat-9")), statement(2, Acme)))
      val body = get(
        h.httpClient,
        s"${h.baseUrl}/api/history/statements?after=0",
        Some(rootToken(h))
      ).body()
      val patIds =
        rows(body, "statements").map(_.hcursor.get[Option[String]]("patId").toOption.flatten)
      patIds shouldBe List(Some("pat-9"), None)
    finally h.shutdown()
  }
