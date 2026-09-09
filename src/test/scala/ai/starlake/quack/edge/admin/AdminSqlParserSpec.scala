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

  "parse" should "parse CREATE ROLE and DROP ROLE [IF EXISTS]" in:
    AdminSqlParser.parse("CREATE ROLE analyst") shouldBe Right(AdminCommand.CreateRole("analyst"))
    AdminSqlParser.parse("create role \"Analyst X\";") shouldBe
      Right(AdminCommand.CreateRole("Analyst X"))
    AdminSqlParser.parse("DROP ROLE analyst") shouldBe
      Right(AdminCommand.DropRole("analyst", ifExists = false))
    AdminSqlParser.parse("DROP ROLE IF EXISTS analyst") shouldBe
      Right(AdminCommand.DropRole("analyst", ifExists = true))

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
