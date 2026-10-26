package ai.starlake.quack.mcp

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  PoolPermissionHandlers,
  RoleColumnPolicyHandlers,
  RoleCreateRequest,
  RoleHandlers,
  RoleRowPolicyHandlers,
  UserCreateRequest,
  UserHandlers
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, UserStore}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Access-tier MCP tool contract over the in-memory supervisor: role table permissions, column
  * policies (masking), row policies, and pool permissions.
  */
class McpAccessToolsSpec extends AnyFlatSpec with Matchers:

  private val Tenant0 = "acme"

  private def makeDuckDbUserStore(): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    val tmpFile = java.nio.file.Files.createTempFile("qod-mcp-access-test-users", ".duckdb")
    tmpFile.toFile.delete()
    tmpFile.toFile.deleteOnExit()
    val jdbcUrl = InProcessDuckDb.url(tmpFile.toAbsolutePath.toString)
    val c       = java.sql.DriverManager.getConnection(jdbcUrl)
    try
      c.createStatement()
        .execute(
          // Mirrors the real qodstate_user schema (Liquibase 0003 + 0006 + 0022 + 0028 + 0029 +
          // 0030): UserUpsert's ON CONFLICT clause unconditionally touches enabled,
          // must_change_password, email, failed_attempts and locked_at, so all must exist even
          // though this fixture never runs Liquibase against DuckDB.
          """CREATE TABLE IF NOT EXISTS qodstate_user (
          |  id                    TEXT PRIMARY KEY,
          |  tenant                TEXT,
          |  username              TEXT NOT NULL,
          |  password_hash         TEXT NOT NULL,
          |  kind                  TEXT NOT NULL DEFAULT 'user',
          |  enabled               BOOLEAN NOT NULL DEFAULT true,
          |  must_change_password  BOOLEAN NOT NULL DEFAULT false,
          |  email                 TEXT,
          |  failed_attempts       INT NOT NULL DEFAULT 0,
          |  locked_at             TIMESTAMPTZ,
          |  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
        )
    finally c.close()
    new UserStore(jdbcUrl, "", "")

  private final class Fixture:
    val store   = new InMemoryControlPlaneStore()
    val backend = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.restore()
    sup.createTenant(Tenant(Tenant0)).unsafeRunSync()

    // Every test in this spec calls tools as McpPrincipal.StaticKey (no token: a superuser, like
    // Main's transitional MCP wiring), and the handler-level calls below (newRoleId, newUserId)
    // pass no credential either, so every gate sees a superuser.
    val scopeOf: SessionScope.Resolver = _ => SessionScope.Superuser

    val userStore       = makeDuckDbUserStore()
    val users           = new UserHandlers(sup, userStore)
    val roleHandlers    = new RoleHandlers(sup, users)
    val columnPolicies  = new RoleColumnPolicyHandlers(sup)
    val rowPolicies     = new RoleRowPolicyHandlers(sup)
    val poolPermissions = new PoolPermissionHandlers(sup, users)

    val tools =
      new McpAccessTools(roleHandlers, columnPolicies, rowPolicies, poolPermissions, scopeOf)

    def call(name: String, principal: McpPrincipal, args: (String, Json)*): Either[String, Json] =
      val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
      tool.adminOnly shouldBe true
      tool.run(principal, JsonObject(args*)).unsafeRunSync()

    def idOf(out: Either[String, Json]): String =
      out.toOption.get.hcursor.get[String]("id").toOption.get

    def newRoleId(): String =
      roleHandlers
        .createRole(RoleCreateRequest(Tenant0, "reader"), None)(scopeOf)
        .unsafeRunSync()
        .toOption
        .get
        .id

    def newUserId(): String =
      users
        .createUser(
          UserCreateRequest(
            tenant = Some(Tenant0),
            username = "bob",
            password = "s3cret-s3cret"
          ),
          None
        )(scopeOf)
        .unsafeRunSync()
        .toOption
        .get
        .id

  "grant_role_permission" should "grant RO on a table, list it, and revoke it" in {
    val f      = new Fixture
    val roleId = f.newRoleId()
    val out    = f.call(
      "grant_role_permission",
      McpPrincipal.StaticKey,
      "role_id" -> Json.fromString(roleId),
      "schema"  -> Json.fromString("main"),
      "table"   -> Json.fromString("customer"),
      "verb"    -> Json.fromString("RO")
    )
    out.isRight shouldBe true
    val permId = f.idOf(out)
    f.call("list_role_permissions", McpPrincipal.StaticKey, "role_id" -> Json.fromString(roleId))
      .toOption
      .get
      .hcursor
      .downField("permissions")
      .values
      .get
      .size shouldBe 1
    f.call("revoke_role_permission", McpPrincipal.StaticKey, "id" -> Json.fromString(permId))
      .isRight shouldBe true
    f.call("list_role_permissions", McpPrincipal.StaticKey, "role_id" -> Json.fromString(roleId))
      .toOption
      .get
      .hcursor
      .downField("permissions")
      .values
      .get
      .size shouldBe 0
  }

  "create_column_policy" should "mask a column, update to deny, then delete" in {
    val f       = new Fixture
    val roleId  = f.newRoleId()
    val created = f.call(
      "create_column_policy",
      McpPrincipal.StaticKey,
      "role_id"       -> Json.fromString(roleId),
      "column_name"   -> Json.fromString("c_phone"),
      "action"        -> Json.fromString("mask"),
      "transform_sql" -> Json.fromString("'***'")
    )
    created.isRight shouldBe true
    val id = f.idOf(created)
    f.call(
      "update_column_policy",
      McpPrincipal.StaticKey,
      "id"     -> Json.fromString(id),
      "action" -> Json.fromString("deny")
    ).isRight shouldBe true
    f.call("list_column_policies", McpPrincipal.StaticKey, "role_id" -> Json.fromString(roleId))
      .toOption
      .get
      .hcursor
      .downField("policies")
      .values
      .get
      .size shouldBe 1
    f.call("delete_column_policy", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
      .isRight shouldBe true
  }

  "create_row_policy" should "install a predicate, update it, then delete" in {
    val f       = new Fixture
    val roleId  = f.newRoleId()
    val created = f.call(
      "create_row_policy",
      McpPrincipal.StaticKey,
      "role_id"       -> Json.fromString(roleId),
      "predicate_sql" -> Json.fromString("c_mktsegment = 'BUILDING'")
    )
    created.isRight shouldBe true
    val id = f.idOf(created)
    f.call(
      "update_row_policy",
      McpPrincipal.StaticKey,
      "id"            -> Json.fromString(id),
      "predicate_sql" -> Json.fromString("c_mktsegment = 'MACHINERY'")
    ).isRight shouldBe true
    f.call("list_row_policies", McpPrincipal.StaticKey, "role_id" -> Json.fromString(roleId))
      .toOption
      .get
      .hcursor
      .downField("policies")
      .values
      .get
      .size shouldBe 1
    f.call("delete_row_policy", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
      .isRight shouldBe true
  }

  "grant_pool_permission" should "grant tenant-wide access to a user and list it" in {
    val f      = new Fixture
    val userId = f.newUserId()
    val out    = f.call(
      "grant_pool_permission",
      McpPrincipal.StaticKey,
      "tenant"  -> Json.fromString(Tenant0),
      "user_id" -> Json.fromString(userId)
    )
    out.isRight shouldBe true
    val permId = f.idOf(out)
    f.call("list_pool_permissions", McpPrincipal.StaticKey, "tenant" -> Json.fromString(Tenant0))
      .toOption
      .get
      .hcursor
      .downField("permissions")
      .values
      .get
      // createTenant also seeds the built-in qod_all_pools group grant; count the user's only.
      .count(_.hcursor.get[String]("userId").toOption.contains(userId)) shouldBe 1
    f.call("revoke_pool_permission", McpPrincipal.StaticKey, "id" -> Json.fromString(permId))
      .isRight shouldBe true
  }

  it should "surface the handler error when both user_id and group_id are set" in {
    val f      = new Fixture
    val userId = f.newUserId()
    val out    = f.call(
      "grant_pool_permission",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "user_id"  -> Json.fromString(userId),
      "group_id" -> Json.fromString("g-nope")
    )
    out.isLeft shouldBe true
  }
