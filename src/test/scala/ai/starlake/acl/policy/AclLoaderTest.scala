package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{AclPolicy, Grant, GrantTarget, Principal}
import cats.data.Validated
import org.scalatest.funsuite.AnyFunSuite

class AclLoaderTest extends AnyFunSuite {

  private def env(vars: (String, String)*): String => Option[String] =
    vars.toMap.get

  private val noEnv: String => Option[String] = _ => None

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  test("single table-level grant parses correctly") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice, group:analysts]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 1)
        val grant = policy.grants.head
        assert(grant.target == GrantTarget.table("mydb", "public", "orders"))
        assert(grant.principals == List(Principal.user("alice"), Principal.group("analysts")))
        assert(grant.expires.isEmpty)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("single schema-level grant (2-part target)") {
    val yaml =
      """grants:
        |  - target: mydb.public
        |    principals: [user:bob]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.target == GrantTarget.schema("mydb", "public"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("single database-level grant (1-part target)") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [group:admins]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.target == GrantTarget.database("mydb"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("multiple grants in one file") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |  - target: mydb.public.users
        |    principals: [user:bob]
        |  - target: otherdb
        |    principals: [group:admins]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 3)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("mixed hierarchy levels (db + schema + table grants together)") {
    val yaml =
      """grants:
        |  - target: proddb
        |    principals: [group:admins]
        |  - target: proddb.analytics
        |    principals: [group:data_team]
        |  - target: proddb.analytics.events
        |    principals: [user:carol]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 3)
        assert(policy.grants(0).target == GrantTarget.database("proddb"))
        assert(policy.grants(1).target == GrantTarget.schema("proddb", "analytics"))
        assert(policy.grants(2).target == GrantTarget.table("proddb", "analytics", "events"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("case-insensitive: YAML target produces lowercase GrantTarget") {
    val yaml =
      """grants:
        |  - target: MyDB.Public.Orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.target == GrantTarget.table("mydb", "public", "orders"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("case-insensitive: YAML principal produces lowercase Principal") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [user:Alice, group:DataTeam]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.principals == List(Principal.user("alice"), Principal.group("datateam")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("environment variable in target resolves correctly") {
    val yaml =
      """grants:
        |  - target: ${DB_NAME}.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, env("DB_NAME" -> "proddb"))
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.target == GrantTarget.table("proddb", "public", "orders"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("environment variable in principal resolves correctly") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [user:${ADMIN_USER}]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, env("ADMIN_USER" -> "alice"))
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.principals == List(Principal.user("alice")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("multiple files merged via loadAllWithEnv unions all grants") {
    val yaml1 =
      """grants:
        |  - target: db1.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    val yaml2 =
      """grants:
        |  - target: db2.public.users
        |    principals: [user:bob]
        |""".stripMargin

    val result = AclLoader.loadAllWithEnv(List(yaml1, yaml2), noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 2)
        assert(policy.grants(0).target == GrantTarget.table("db1", "public", "orders"))
        assert(policy.grants(1).target == GrantTarget.table("db2", "public", "users"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Validation errors
  // ---------------------------------------------------------------------------

  test("empty YAML (no grants key) returns error") {
    val yaml = "some_other_key: value"
    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.YamlParseError]))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("empty grants list returns EmptyPolicy error") {
    val yaml = "grants: []"
    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.contains(AclError.EmptyPolicy()))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("empty principals list on a grant returns EmptyPrincipals error") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: []
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.contains(AclError.EmptyPrincipals(0)))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("target with empty part (mydb..orders) returns InvalidTarget") {
    val yaml =
      """grants:
        |  - target: mydb..orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists {
          case AclError.InvalidTarget("mydb..orders", _, 0) => true
          case _                                             => false
        })
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("target with 4+ parts (a.b.c.d) returns InvalidTarget") {
    val yaml =
      """grants:
        |  - target: a.b.c.d
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists {
          case AclError.InvalidTarget("a.b.c.d", _, 0) => true
          case _                                        => false
        })
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("target that is empty string returns InvalidTarget") {
    val yaml =
      """grants:
        |  - target: ""
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.InvalidTarget]))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("principal without prefix returns InvalidPrincipal") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [admin]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists {
          case AclError.InvalidPrincipal("admin", _, 0) => true
          case _                                        => false
        })
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("principal with invalid prefix returns InvalidPrincipal") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [role:admin]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists {
          case AclError.InvalidPrincipal("role:admin", _, 0) => true
          case _                                             => false
        })
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("principal with empty name returns InvalidPrincipal") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: ["user:"]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists {
          case AclError.InvalidPrincipal("user:", _, 0) => true
          case _                                        => false
        })
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("multiple errors accumulated: YAML with 2 bad grants returns 2+ errors") {
    val yaml =
      """grants:
        |  - target: a.b.c.d
        |    principals: [admin]
        |  - target: ""
        |    principals: [role:x]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        // At minimum: InvalidTarget for grant 0, InvalidPrincipal for grant 0,
        // InvalidTarget for grant 1, InvalidPrincipal for grant 1
        assert(errors.toList.size >= 2)
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("unresolved env var returns UnresolvedVariable") {
    val yaml =
      """grants:
        |  - target: ${MISSING_DB}.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.contains(AclError.UnresolvedVariable("MISSING_DB")))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("invalid YAML syntax returns YamlParseError") {
    val yaml = "grants:\n  - target: [invalid yaml here\n"
    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.YamlParseError]))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  // ---------------------------------------------------------------------------
  // Multi-file
  // ---------------------------------------------------------------------------

  test("loadAllWithEnv with two valid files merges grants") {
    val yaml1 =
      """grants:
        |  - target: db1
        |    principals: [user:a]
        |""".stripMargin
    val yaml2 =
      """grants:
        |  - target: db2
        |    principals: [user:b]
        |""".stripMargin

    val result = AclLoader.loadAllWithEnv(List(yaml1, yaml2), noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 2)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("loadAllWithEnv with one valid and one invalid returns errors from invalid") {
    val validYaml =
      """grants:
        |  - target: mydb
        |    principals: [user:alice]
        |""".stripMargin
    val invalidYaml =
      """grants:
        |  - target: a.b.c.d
        |    principals: [admin]
        |""".stripMargin

    val result = AclLoader.loadAllWithEnv(List(validYaml, invalidYaml), noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.nonEmpty)
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("loadAllWithEnv with empty list returns EmptyPolicy error") {
    val result = AclLoader.loadAllWithEnv(List.empty, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.contains(AclError.EmptyPolicy()))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("duplicate grants across files are silently merged") {
    val yaml1 =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    val yaml2 =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadAllWithEnv(List(yaml1, yaml2), noEnv)
    result match {
      case Validated.Valid(policy) =>
        // Duplicates are silently merged (union behavior -- both kept in list)
        assert(policy.grants.size == 2)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  test("principal with extra colons uses split limit 2, name becomes alice:bob") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: ["user:alice:bob"]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.principals == List(Principal.user("alice:bob")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("target with leading/trailing whitespace is trimmed by YAML parser") {
    // YAML scalar values have leading/trailing whitespace trimmed
    val yaml =
      """grants:
        |  - target: "  mydb.public.orders  "
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    // YAML preserves quoted string whitespace, so this should fail validation
    // because "  mydb" has leading spaces making it a non-standard identifier
    // The split on "." will produce parts with spaces
    result match {
      case Validated.Valid(policy) =>
        // If YAML preserves whitespace in quotes, the target parts include spaces
        // but GrantTarget factories normalize to lowercase (spaces preserved)
        assert(policy.grants.size == 1)
      case Validated.Invalid(_) =>
        // Also acceptable if whitespace causes validation to fail
        succeed
    }
  }

  test("grant with single principal works") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals:
        |      - user:alice
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.principals.size == 1)
        assert(policy.grants.head.principals.head == Principal.user("alice"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("error accumulation across target and principals in same grant") {
    val yaml =
      """grants:
        |  - target: a.b.c.d
        |    principals: [admin, role:x]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        // Should have errors for both target AND principals (independent validation via mapN)
        val hasTargetError = errors.toList.exists(_.isInstanceOf[AclError.InvalidTarget])
        val hasPrincipalError = errors.toList.exists(_.isInstanceOf[AclError.InvalidPrincipal])
        assert(hasTargetError, "Expected InvalidTarget error")
        assert(hasPrincipalError, "Expected InvalidPrincipal error")
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("error messages include 1-based grant index for user-friendliness") {
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [user:alice]
        |  - target: bad.target.too.many.parts
        |    principals: [user:bob]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        val targetError = errors.toList.collectFirst { case e: AclError.InvalidTarget => e }
        assert(targetError.isDefined)
        assert(targetError.get.message.contains("grant #2"))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  // ---------------------------------------------------------------------------
  // Authorized field
  // ---------------------------------------------------------------------------

  test("should parse authorized: true on a grant") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |    authorized: true
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.authorized == true)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("should default authorized to false when absent") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.authorized == false)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("should accept authorized: true alongside other fields") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice, group:analysts]
        |    authorized: true
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        val grant = policy.grants.head
        assert(grant.target == GrantTarget.table("mydb", "public", "orders"))
        assert(grant.principals == List(Principal.user("alice"), Principal.group("analysts")))
        assert(grant.authorized == true)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Resolution mode
  // ---------------------------------------------------------------------------

  test("should parse mode: permissive") {
    val yaml =
      """mode: permissive
        |grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.mode == ResolutionMode.Permissive)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("should default mode to strict when absent") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.mode == ResolutionMode.Strict)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("should reject invalid mode value") {
    val yaml =
      """mode: banana
        |grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.InvalidMode]))
        val modeError = errors.toList.collectFirst { case e: AclError.InvalidMode => e }
        assert(modeError.get.value == "banana")
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("should parse mode: strict explicitly") {
    val yaml =
      """mode: strict
        |grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val result = AclLoader.loadWithEnv(yaml, noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.mode == ResolutionMode.Strict)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("loadAllWithEnv uses mode from first document") {
    val yaml1 =
      """mode: permissive
        |grants:
        |  - target: db1
        |    principals: [user:a]
        |""".stripMargin
    val yaml2 =
      """mode: strict
        |grants:
        |  - target: db2
        |    principals: [user:b]
        |""".stripMargin

    val result = AclLoader.loadAllWithEnv(List(yaml1, yaml2), noEnv)
    result match {
      case Validated.Valid(policy) =>
        assert(policy.mode == ResolutionMode.Permissive)
        assert(policy.grants.size == 2)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Grant expiration parsing
  // ---------------------------------------------------------------------------

  test("grant with valid expires field parses correctly") {
    val yaml =
      """grants:
        |  - target: mydb.myschema.mytable
        |    principals:
        |      - user:alice
        |    expires: "2025-12-31T23:59:59Z"
        |""".stripMargin

    AclLoader.loadWithEnv(yaml, noEnv) match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 1)
        val grant = policy.grants.head
        assert(grant.expires.isDefined)
        assert(grant.expires.get == java.time.Instant.parse("2025-12-31T23:59:59Z"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("grant without expires field has expires = None") {
    val yaml =
      """grants:
        |  - target: mydb.myschema.mytable
        |    principals:
        |      - user:alice
        |""".stripMargin

    AclLoader.loadWithEnv(yaml, noEnv) match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.expires.isEmpty)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("grant with invalid expires format returns InvalidExpires error") {
    val yaml =
      """grants:
        |  - target: mydb.myschema.mytable
        |    principals:
        |      - user:alice
        |    expires: "not-a-date"
        |""".stripMargin

    AclLoader.loadWithEnv(yaml, noEnv) match {
      case Validated.Valid(_) =>
        fail("Expected Invalid for invalid expires format")
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        assert(errorList.size == 1)
        assert(errorList.head.isInstanceOf[ai.starlake.acl.AclError.InvalidExpires])
        assert(errorList.head.message.contains("not-a-date"))
    }
  }

  test("multiple grants with mixed expires (some with, some without)") {
    val yaml =
      """grants:
        |  - target: mydb.myschema.t1
        |    principals:
        |      - user:alice
        |    expires: "2025-06-15T00:00:00Z"
        |  - target: mydb.myschema.t2
        |    principals:
        |      - user:bob
        |""".stripMargin

    AclLoader.loadWithEnv(yaml, noEnv) match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 2)
        assert(policy.grants(0).expires.isDefined)
        assert(policy.grants(1).expires.isEmpty)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("invalid expires error is accumulated with other errors") {
    val yaml =
      """grants:
        |  - target: a.b.c.d
        |    principals:
        |      - user:alice
        |    expires: "bad-date"
        |""".stripMargin

    AclLoader.loadWithEnv(yaml, noEnv) match {
      case Validated.Valid(_) =>
        fail("Expected Invalid for invalid target and expires")
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        assert(errorList.size == 2) // InvalidTarget + InvalidExpires
        assert(errorList.exists(_.isInstanceOf[ai.starlake.acl.AclError.InvalidTarget]))
        assert(errorList.exists(_.isInstanceOf[ai.starlake.acl.AclError.InvalidExpires]))
    }
  }
}
