package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class AccessResultTest extends AnyFunSuite with Matchers {

  private val now  = Instant.parse("2026-01-15T10:30:00Z")
  private val user = UserIdentity("alice", Set("eng"))

  private val allowedTable = TableRef("db", "schema", "allowed_table")
  private val deniedTable  = TableRef("db", "schema", "denied_table")

  private val allowedAccess = TableAccess(
    table = allowedTable,
    decision = Decision.Allowed,
    matchedGrant = Some(Grant(GrantTarget.all, List(Principal.AllUsers))),
    denyReason = None
  )

  private val deniedAccess = TableAccess(
    table = deniedTable,
    decision = Decision.Denied,
    matchedGrant = None,
    denyReason = Some(DenyReason.NoMatchingGrant(deniedTable, user))
  )

  private val allowedResult = AccessResult(
    decision = Decision.Allowed,
    sql = "SELECT * FROM allowed_table",
    user = user,
    timestamp = now,
    tableAccesses = List(allowedAccess),
    viewResolutions = Nil
  )

  private val deniedResult = AccessResult(
    decision = Decision.Denied,
    sql = "SELECT * FROM allowed_table JOIN denied_table",
    user = user,
    timestamp = now,
    tableAccesses = List(allowedAccess, deniedAccess),
    viewResolutions = Nil
  )

  test("isAllowed returns true for Allowed decision") {
    allowedResult.isAllowed shouldBe true
  }

  test("isAllowed returns false for Denied decision") {
    deniedResult.isAllowed shouldBe false
  }

  test("deniedTables filters correctly") {
    deniedResult.deniedTables shouldBe List(deniedTable)
    allowedResult.deniedTables shouldBe Nil
  }

  test("allowedTables filters correctly") {
    deniedResult.allowedTables shouldBe List(allowedTable)
    allowedResult.allowedTables shouldBe List(allowedTable)
  }

  test("allTables returns all tables") {
    deniedResult.allTables shouldBe List(allowedTable, deniedTable)
    allowedResult.allTables shouldBe List(allowedTable)
  }

  test("toJson produces valid JSON with expected structure") {
    val json   = deniedResult.toJson
    val cursor = json.hcursor

    cursor.get[String]("decision").toOption shouldBe Some("denied")
    cursor.get[String]("sql").toOption shouldBe Some("SELECT * FROM allowed_table JOIN denied_table")
    cursor.get[String]("timestamp").toOption shouldBe Some("2026-01-15T10:30:00Z")

    val userJson = cursor.downField("user")
    userJson.get[String]("name").toOption shouldBe Some("alice")

    val tablesJson = cursor.downField("tables")
    tablesJson.downField("allowed").focus.flatMap(_.asArray).map(_.size) shouldBe Some(1)
    tablesJson.downField("denied").focus.flatMap(_.asArray).map(_.size) shouldBe Some(1)
  }

  test("toJson encodes table accesses with decision and details") {
    val json     = allowedResult.toJson
    val accesses = json.hcursor.downField("tableAccesses").focus.flatMap(_.asArray)
    accesses shouldBe defined
    accesses.get.size shouldBe 1

    val firstAccess = accesses.get.head.hcursor
    firstAccess.get[String]("decision").toOption shouldBe Some("allowed")
  }

  test("toJson encodes view resolutions") {
    val view         = TableRef("db", "schema", "my_view")
    val underlying   = TableRef("db", "schema", "base_table")
    val resultWithVR = allowedResult.copy(viewResolutions = List(ViewResolution(view, List(underlying))))
    val json         = resultWithVR.toJson
    val vrs          = json.hcursor.downField("viewResolutions").focus.flatMap(_.asArray)
    vrs shouldBe defined
    vrs.get.size shouldBe 1
  }
}
