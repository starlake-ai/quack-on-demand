package ai.starlake.quack.edge.admin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AdminSqlParserSpec extends AnyFlatSpec with Matchers:

  "claims" should "claim admin first-keyword forms" in:
    AdminSqlParser.claims("GRANT SELECT ON t TO ROLE r") shouldBe true
    AdminSqlParser.claims("revoke all on t from role r") shouldBe true
    AdminSqlParser.claims("CREATE ROLE analyst") shouldBe true
    AdminSqlParser.claims("DROP ROLE analyst") shouldBe true
    AdminSqlParser.claims("ALTER GROUP g ADD USER u") shouldBe true

  it should "not claim data statements" in:
    AdminSqlParser.claims("SELECT 1") shouldBe false
    AdminSqlParser.claims("CREATE TABLE t (a INT)") shouldBe false
    AdminSqlParser.claims("DROP TABLE t") shouldBe false
    AdminSqlParser.claims("ALTER TABLE t ADD COLUMN c INT") shouldBe false
    AdminSqlParser.claims("SHOW TABLES") shouldBe false
    AdminSqlParser.claims("\"GRANT\"") shouldBe false // quoted ident, not a keyword

  it should "not be fooled by nested block comments hiding the real first keyword" in:
    AdminSqlParser.claims(
      "/* /* */ GRANT ROLE r TO USER u */ select 1"
    ) shouldBe false
    AdminSqlParser.claims(
      "CREATE /* /* */ ROLE */ TABLE t(a int)"
    ) shouldBe false

  it should "claim/parse past a leading comment before the first keyword" in:
    AdminSqlParser.claims("-- x\nCREATE ROLE r") shouldBe true
    AdminSqlParser.parse("-- x\nCREATE ROLE r") shouldBe Right(AdminCommand.CreateRole("r"))
    AdminSqlParser.claims("/* x */ CREATE ROLE r") shouldBe true
    AdminSqlParser.parse("/* x */ CREATE ROLE r") shouldBe Right(AdminCommand.CreateRole("r"))

  it should "tolerate a trailing line comment with no closing newline" in:
    AdminSqlParser.parse("CREATE ROLE r -- trailing comment") shouldBe
      Right(AdminCommand.CreateRole("r"))

  "parse" should "parse CREATE ROLE and DROP ROLE [IF EXISTS]" in:
    AdminSqlParser.parse("CREATE ROLE analyst") shouldBe Right(AdminCommand.CreateRole("analyst"))
    AdminSqlParser.parse("create role \"Analyst X\";") shouldBe
      Right(AdminCommand.CreateRole("Analyst X"))
    AdminSqlParser.parse("DROP ROLE analyst") shouldBe
      Right(AdminCommand.DropRole("analyst", ifExists = false))
    AdminSqlParser.parse("DROP ROLE IF EXISTS analyst") shouldBe
      Right(AdminCommand.DropRole("analyst", ifExists = true))

  it should "not swallow a quoted \"IF\" identifier as the IF EXISTS keyword" in:
    AdminSqlParser.parse("DROP ROLE \"IF\"") shouldBe
      Right(AdminCommand.DropRole("IF", ifExists = false))

  it should "unescape a doubled double-quote inside a quoted identifier" in:
    AdminSqlParser.parse("CREATE ROLE \"an\"\"alyst\"") shouldBe
      Right(AdminCommand.CreateRole("an\"alyst"))

  it should "parse role membership grants" in:
    AdminSqlParser.parse("GRANT ROLE analyst TO USER alice") shouldBe
      Right(AdminCommand.GrantRoleTo("analyst", Principal.User("alice")))
    AdminSqlParser.parse("GRANT ROLE analyst TO GROUP finance") shouldBe
      Right(AdminCommand.GrantRoleTo("analyst", Principal.Group("finance")))
    AdminSqlParser.parse("REVOKE ROLE analyst FROM USER alice") shouldBe
      Right(AdminCommand.RevokeRoleFrom("analyst", Principal.User("alice")))

  it should "parse ALTER GROUP membership" in:
    AdminSqlParser.parse("ALTER GROUP finance ADD USER alice") shouldBe
      Right(AdminCommand.AlterGroupAddUser("finance", "alice"))
    AdminSqlParser.parse("ALTER GROUP finance DROP USER alice") shouldBe
      Right(AdminCommand.AlterGroupDropUser("finance", "alice"))

  it should "reject trailing garbage and malformed input with Left" in:
    AdminSqlParser.parse("CREATE ROLE analyst extra").isLeft shouldBe true
    AdminSqlParser.parse("ALTER GROUP g RENAME TO h").isLeft shouldBe true
    AdminSqlParser.parse("GRANT ROLE r TO alice").isLeft shouldBe true // missing USER/GROUP

  it should "fail closed on an unterminated quoted identifier" in:
    AdminSqlParser.claims("CREATE ROLE \"unterminated") shouldBe false
    AdminSqlParser.parse("CREATE ROLE \"unterminated").isLeft shouldBe true

  it should "fail closed on an unterminated string literal" in:
    AdminSqlParser.claims("CREATE ROLE r 'unterminated") shouldBe false
    AdminSqlParser.parse("CREATE ROLE r 'unterminated").isLeft shouldBe true

  it should "fail closed on an unterminated block comment" in:
    AdminSqlParser.claims("CREATE ROLE r /* unterminated") shouldBe false
    AdminSqlParser.parse("CREATE ROLE r /* unterminated").isLeft shouldBe true

  it should "reject empty, whitespace-only, and bare-semicolon input" in:
    AdminSqlParser.parse("").isLeft shouldBe true
    AdminSqlParser.parse("   ").isLeft shouldBe true
    AdminSqlParser.parse(";").isLeft shouldBe true

  it should "leave the not-yet-implemented placeholder arms returning Left" in:
    // Task 4 replaces this arm with real grammar; flipping it to Right is a conscious
    // update to this test, not an accidental regression.
    AdminSqlParser.parse("SHOW ROLES").isLeft shouldBe true

  "parse GRANT/REVOKE on tables" should "map privilege lists onto RO/RW/DDL/ALL" in:
    AdminSqlParser.parse("GRANT SELECT ON tpch.main.orders TO ROLE analyst") shouldBe
      Right(AdminCommand.GrantTable("RO", TableRef("tpch", "main", "orders"), "analyst"))
    AdminSqlParser.parse("GRANT INSERT, UPDATE, DELETE ON TABLE tpch.main.orders TO etl") shouldBe
      Right(AdminCommand.GrantTable("RW", TableRef("tpch", "main", "orders"), "etl"))
    AdminSqlParser.parse("GRANT SELECT, INSERT ON t TO ROLE etl") shouldBe
      Right(AdminCommand.GrantTable("RW", TableRef("*", "*", "t"), "etl"))
    AdminSqlParser.parse("GRANT DDL ON tpch.main.* TO ROLE dba") shouldBe
      Right(AdminCommand.GrantTable("DDL", TableRef("tpch", "main", "*"), "dba"))
    AdminSqlParser.parse("GRANT ALL PRIVILEGES ON *.*.* TO ROLE root_like") shouldBe
      Right(AdminCommand.GrantTable("ALL", TableRef("*", "*", "*"), "root_like"))

  it should "pad short table refs with wildcards" in:
    AdminSqlParser.parse("GRANT SELECT ON main.orders TO ROLE r") shouldBe
      Right(AdminCommand.GrantTable("RO", TableRef("*", "main", "orders"), "r"))

  it should "reject bad privilege combinations" in:
    AdminSqlParser.parse("GRANT DDL, SELECT ON t TO ROLE r").isLeft shouldBe true
    AdminSqlParser.parse("GRANT ALL, SELECT ON t TO ROLE r").isLeft shouldBe true
    AdminSqlParser.parse("GRANT FROBNICATE ON t TO ROLE r").isLeft shouldBe true

  it should "parse REVOKE with verb and REVOKE ALL as any-verb" in:
    AdminSqlParser.parse("REVOKE SELECT ON tpch.main.orders FROM ROLE analyst") shouldBe
      Right(AdminCommand.RevokeTable(Some("RO"), TableRef("tpch", "main", "orders"), "analyst"))
    AdminSqlParser.parse("REVOKE ALL ON tpch.main.orders FROM analyst") shouldBe
      Right(AdminCommand.RevokeTable(None, TableRef("tpch", "main", "orders"), "analyst"))

  "parse GRANT/REVOKE CONNECT ON POOL" should "handle qualified and bare pool names" in:
    AdminSqlParser.parse("GRANT CONNECT ON POOL tpch.bi TO USER alice") shouldBe
      Right(AdminCommand.GrantPool(PoolTarget(Some("tpch"), "bi"), Principal.User("alice")))
    AdminSqlParser.parse("GRANT CONNECT ON POOL bi TO GROUP finance") shouldBe
      Right(AdminCommand.GrantPool(PoolTarget(None, "bi"), Principal.Group("finance")))
    AdminSqlParser.parse("REVOKE CONNECT ON POOL bi FROM USER alice") shouldBe
      Right(AdminCommand.RevokePool(PoolTarget(None, "bi"), Principal.User("alice")))

  it should "claim a GRANT ROLE ... unterminated block comment past the claims token budget" in:
    AdminSqlParser.claims("GRANT ROLE r TO USER u /* unterminated") shouldBe true
