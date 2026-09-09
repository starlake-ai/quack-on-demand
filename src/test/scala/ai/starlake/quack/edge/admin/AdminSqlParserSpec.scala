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

  "parse GRANT/REVOKE on tables" should "map privilege lists onto RO/RW/DDL/ALL" in:
    AdminSqlParser.parse("GRANT SELECT ON t TO ROLE r") shouldBe
      Right(AdminCommand.GrantTable("RO", TableRef("*", "*", "t"), "r"))
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

  it should "reject a quoted \"*\" segment as a literal, not the wildcard" in:
    AdminSqlParser.parse("GRANT ALL ON \"*\".\"*\".\"*\" TO ROLE r").isLeft shouldBe true

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

  "parse row policies" should "extract the raw predicate between balanced parens" in:
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON tpch.main.orders FOR ROLE analyst " +
        "USING (region = ${tenantId} OR owner = ${user})"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(
        TableRef("tpch", "main", "orders"),
        "analyst",
        "region = ${tenantId} OR owner = ${user}",
        orReplace = false
      )
    )
    AdminSqlParser.parse(
      "CREATE OR REPLACE ROW POLICY ON t FOR ROLE r USING (a IN ('x)', 'y'))"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(TableRef("*", "*", "t"), "r", "a IN ('x)', 'y')", true)
    )
    AdminSqlParser.parse("DROP ROW POLICY IF EXISTS ON t FOR ROLE r") shouldBe
      Right(AdminCommand.DropRowPolicy(TableRef("*", "*", "t"), "r", ifExists = true))

  it should "reject unbalanced or empty predicates" in:
    AdminSqlParser.parse("CREATE ROW POLICY ON t FOR ROLE r USING (a = (1)").isLeft shouldBe true
    AdminSqlParser.parse("CREATE ROW POLICY ON t FOR ROLE r USING ()").isLeft shouldBe true
    AdminSqlParser.parse("CREATE ROW POLICY ON t FOR ROLE r USING (1=1) extra").isLeft shouldBe true

  it should "not let a comment's ')' or ';' influence paren depth or the semicolon guard" in:
    AdminSqlParser
      .parse(
        "CREATE ROW POLICY ON t FOR ROLE r USING (a = 1 -- )"
      )
      .isLeft shouldBe true
    AdminSqlParser
      .parse(
        "CREATE ROW POLICY ON t FOR ROLE r USING (a = 1 /* ) */"
      )
      .isLeft shouldBe true
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON t FOR ROLE r USING (a = 1 /* ) */ AND b = 2)"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(
        TableRef("*", "*", "t"),
        "r",
        "a = 1 /* ) */ AND b = 2",
        false
      )
    )
    AdminSqlParser.parse("CREATE ROW POLICY ON t FOR ROLE r USING (a=1;)").isLeft shouldBe true

  it should "reject a body whose last line comment is not followed by further content" in:
    AdminSqlParser
      .parse(
        "CREATE ROW POLICY ON t FOR ROLE r USING (\n" +
          "  tenant = ${tenantId} -- scope to caller\n" +
          ")"
      )
      .isLeft shouldBe true

  it should "keep internal newlines and reject nothing when no trailing comment is present" in:
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON t FOR ROLE r USING (\n  a = 1\n  AND b = 2\n)"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(TableRef("*", "*", "t"), "r", "a = 1\n  AND b = 2", false)
    )

  it should "accept a mid-body comment followed by more content on a later line" in:
    AdminSqlParser
      .parse(
        "CREATE ROW POLICY ON t FOR ROLE r USING (a = 1 -- note\n AND b = 2)"
      )
      .isRight shouldBe true
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON t FOR ROLE r USING (a = 1 -- note\n AND b = 2)"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(TableRef("*", "*", "t"), "r", "a = 1 -- note\n AND b = 2", false)
    )

  it should "not treat comment markers or ; inside string literals as comments" in:
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON t FOR ROLE r USING (a = '--' AND b = 2)"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(TableRef("*", "*", "t"), "r", "a = '--' AND b = 2", false)
    )
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON t FOR ROLE r USING (a = '/*' AND b = 2)"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(TableRef("*", "*", "t"), "r", "a = '/*' AND b = 2", false)
    )
    AdminSqlParser.parse(
      "CREATE ROW POLICY ON t FOR ROLE r USING (a = ';' AND b = 2)"
    ) shouldBe Right(
      AdminCommand.CreateRowPolicy(TableRef("*", "*", "t"), "r", "a = ';' AND b = 2", false)
    )

  "parse column policies" should "handle MASK USING and DENY" in:
    AdminSqlParser.parse(
      "CREATE COLUMN POLICY ON tpch.main.customers COLUMN email FOR ROLE analyst " +
        "MASK USING (SHA256(CAST(email AS VARCHAR)))"
    ) shouldBe Right(
      AdminCommand.CreateColumnPolicy(
        TableRef("tpch", "main", "customers"),
        "email",
        "analyst",
        "mask",
        Some("SHA256(CAST(email AS VARCHAR))"),
        orReplace = false
      )
    )
    AdminSqlParser.parse(
      "CREATE OR REPLACE COLUMN POLICY ON customers COLUMN ssn FOR ROLE analyst DENY"
    ) shouldBe Right(
      AdminCommand.CreateColumnPolicy(
        TableRef("*", "*", "customers"),
        "ssn",
        "analyst",
        "deny",
        None,
        orReplace = true
      )
    )
    AdminSqlParser.parse("DROP COLUMN POLICY ON customers COLUMN email FOR ROLE analyst") shouldBe
      Right(
        AdminCommand.DropColumnPolicy(TableRef("*", "*", "customers"), "email", "analyst", false)
      )

  "parse SHOW" should "cover all admin introspection forms" in:
    AdminSqlParser.parse("SHOW ROLES") shouldBe Right(AdminCommand.ShowRoles)
    AdminSqlParser.parse("SHOW GRANTS FOR ROLE analyst") shouldBe
      Right(AdminCommand.ShowGrants("analyst"))
    AdminSqlParser.parse("SHOW ROW POLICIES") shouldBe
      Right(AdminCommand.ShowRowPolicies(PolicyFilter.All))
    AdminSqlParser.parse("SHOW ROW POLICIES ON tpch.main.orders") shouldBe
      Right(AdminCommand.ShowRowPolicies(PolicyFilter.OnTable(TableRef("tpch", "main", "orders"))))
    AdminSqlParser.parse("SHOW COLUMN POLICIES FOR ROLE analyst") shouldBe
      Right(AdminCommand.ShowColumnPolicies(PolicyFilter.ForRole("analyst")))
    AdminSqlParser.parse("SHOW POOL GRANTS") shouldBe Right(AdminCommand.ShowPoolGrants(None))
    AdminSqlParser.parse("SHOW POOL GRANTS FOR USER alice") shouldBe
      Right(AdminCommand.ShowPoolGrants(Some(Principal.User("alice"))))
    AdminSqlParser.parse("SHOW USERS") shouldBe Right(AdminCommand.ShowUsers)

  it should "reject trailing garbage on SHOW USERS" in:
    AdminSqlParser.parse("SHOW USERS extra").isLeft shouldBe true

  "claims on SHOW" should "claim only the admin forms" in:
    AdminSqlParser.claims("SHOW ROLES") shouldBe true
    AdminSqlParser.claims("SHOW GRANTS FOR ROLE r") shouldBe true
    AdminSqlParser.claims("SHOW ROW POLICIES") shouldBe true
    AdminSqlParser.claims("SHOW COLUMN POLICIES") shouldBe true
    AdminSqlParser.claims("SHOW POOL GRANTS") shouldBe true
    AdminSqlParser.claims("SHOW USERS") shouldBe true
    AdminSqlParser.claims("SHOW TABLES") shouldBe false
    AdminSqlParser.claims("SHOW ALL") shouldBe false
    AdminSqlParser.claims("SHOW DATABASES") shouldBe false

  "parse CREATE/DROP USER" should "handle password literals, WITH, ADMIN, IF EXISTS" in:
    AdminSqlParser.parse("CREATE USER alice PASSWORD 'secret'") shouldBe
      Right(AdminCommand.CreateUser("alice", "secret", admin = false))
    AdminSqlParser.parse("CREATE USER alice WITH PASSWORD 'it''s'") shouldBe
      Right(AdminCommand.CreateUser("alice", "it's", admin = false))
    AdminSqlParser.parse("CREATE USER ops PASSWORD 'x' ADMIN") shouldBe
      Right(AdminCommand.CreateUser("ops", "x", admin = true))
    AdminSqlParser.parse("DROP USER alice") shouldBe
      Right(AdminCommand.DropUser("alice", ifExists = false))
    AdminSqlParser.parse("DROP USER IF EXISTS alice") shouldBe
      Right(AdminCommand.DropUser("alice", ifExists = true))

  it should "fail closed on malformed user statements" in:
    AdminSqlParser.parse("CREATE USER alice").isLeft shouldBe true                 // no password
    AdminSqlParser.parse("CREATE USER alice PASSWORD secret").isLeft shouldBe true // not a literal
    AdminSqlParser.parse("CREATE USER alice PASSWORD ''").isLeft shouldBe true     // empty literal
    AdminSqlParser.parse("CREATE USER alice PASSWORD 'x' extra").isLeft shouldBe true
    AdminSqlParser.claims("CREATE USER alice PASSWORD 'x'") shouldBe true
    AdminSqlParser.claims("DROP USER alice") shouldBe true

  it should "reject a double-quoted identifier standing in for the password literal" in:
    // "'" is a QUOTED IDENTIFIER whose content happens to start with ' - not a string
    // literal. Must fail closed, not throw StringIndexOutOfBoundsException.
    AdminSqlParser.parse("CREATE USER a PASSWORD \"'\"").isLeft shouldBe true
    AdminSqlParser.parse("CREATE USER a PASSWORD \"'abc\"").isLeft shouldBe true

  "parse ALTER USER PASSWORD" should "handle the literal and optional WITH" in:
    AdminSqlParser.parse("ALTER USER alice PASSWORD 'newsecret'") shouldBe
      Right(AdminCommand.AlterUserPassword("alice", "newsecret"))
    AdminSqlParser.parse("ALTER USER alice WITH PASSWORD 'it''s'") shouldBe
      Right(AdminCommand.AlterUserPassword("alice", "it's"))
    AdminSqlParser.claims("ALTER USER alice PASSWORD 'x'") shouldBe true

  it should "fail closed on malformed ALTER USER statements" in:
    AdminSqlParser.parse("ALTER USER alice").isLeft shouldBe true                 // no password
    AdminSqlParser.parse("ALTER USER alice PASSWORD secret").isLeft shouldBe true // not a literal
    AdminSqlParser.parse("ALTER USER alice PASSWORD ''").isLeft shouldBe true     // empty literal
    AdminSqlParser.parse("ALTER USER alice PASSWORD 'x' extra").isLeft shouldBe true

  it should "still parse ALTER GROUP forms (regression)" in:
    AdminSqlParser.parse("ALTER GROUP finance ADD USER alice") shouldBe
      Right(AdminCommand.AlterGroupAddUser("finance", "alice"))
    AdminSqlParser.parse("ALTER GROUP finance DROP USER alice") shouldBe
      Right(AdminCommand.AlterGroupDropUser("finance", "alice"))
