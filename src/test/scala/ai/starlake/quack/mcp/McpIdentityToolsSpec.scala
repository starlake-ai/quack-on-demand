package ai.starlake.quack.mcp

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  GroupHandlers,
  MembershipHandlers,
  RoleHandlers,
  TenantHandlers,
  UserHandlers
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, UserStore}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McpIdentityToolsSpec extends AnyFlatSpec with Matchers:

  /** True for a role or group JSON row the tenant got from BuiltinRbac seeding. */
  private def isBuiltin(j: Json): Boolean =
    j.hcursor.get[Boolean]("builtin").toOption.contains(true)

  private val Tenant0  = "acme"
  private val patToken = "qod_pat_alice"

  private def adminPat(tenant: String = Tenant0): McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u1", tenant = Some(tenant), username = "alice", kind = "admin"),
        patId = "pat-1",
        scope = SessionScope(superuser = false, manageableTenants = Set(tenant)),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted
      ),
      patToken
    )

  private def makeDuckDbUserStore(): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    val tmpFile = java.nio.file.Files.createTempFile("qod-mcp-identity-test-users", ".duckdb")
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

    val scopeOf: SessionScope.Resolver =
      t =>
        if t.contains(patToken) then
          SessionScope(superuser = false, manageableTenants = Set(Tenant0))
        else McpSuperuserPat.scopeOf(t)

    val userStore   = makeDuckDbUserStore()
    val users       = new UserHandlers(sup, userStore)
    val tenants     = new TenantHandlers(sup)
    val groups      = new GroupHandlers(sup, users)
    val roles       = new RoleHandlers(sup, users)
    val memberships = new MembershipHandlers(sup, users)

    val locations = new ai.starlake.quack.ondemand.api.TenantLocationHandlers(sup, Some("/srv/qod"))
    val tools     =
      new McpIdentityTools(tenants, users, groups, roles, memberships, scopeOf, locations)

    def call(name: String, principal: McpPrincipal, args: (String, Json)*): Either[String, Json] =
      val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
      tool.adminOnly shouldBe true
      tool.run(principal, JsonObject(args*)).unsafeRunSync()

    def idOf(out: Either[String, Json]): String =
      out.toOption.get.hcursor.get[String]("id").toOption.get

    /** In production, `store` and `userStore` are two Scala wrappers over the SAME physical
      * `qodstate_user` table, so a password hash written via `userStore.upsertUser` (createUser's
      * path) is immediately visible to `store.getPasswordHash` (updateUserPassword's rewrite
      * guard). This fixture deliberately decouples them (in-memory store vs. a real DuckDB
      * UserStore) for speed, so any update that doesn't rotate the password (role/email/enabled-
      * only) needs the hash seeded here first, or `updateUserPassword` refuses with "no stored
      * password hash".
      */
    def seedPasswordHash(tenant: Option[String], username: String): Unit =
      store.upsertUserWithHash(tenant, username, "seed-hash", "user")
      ()

  "create_tenant" should "create a tenant as a superuser PAT and echo it in list_tenants" in {
    val f       = new Fixture
    val created = f.call(
      "create_tenant",
      McpSuperuserPat.principal,
      "id"           -> Json.fromString("acme"),
      "display_name" -> Json.fromString("Acme Corporation")
    )
    created.isRight shouldBe true
    val listed = f.call("list_tenants", McpSuperuserPat.principal)
    listed.toOption.get.hcursor.downField("tenants").values.get.size shouldBe 1
  }

  it should "refuse a tenant-scoped admin PAT (superuser_required)" in {
    val f   = new Fixture
    val out = f.call("create_tenant", adminPat(), "id" -> Json.fromString("evil"))
    out.isLeft shouldBe true
    out.left.toOption.get should include("superuser_required")
  }

  "delete_tenant" should "delete an empty tenant" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val out =
      f.call("delete_tenant", McpSuperuserPat.principal, "name" -> Json.fromString("acme"))
    out.isRight shouldBe true
  }

  "set_tenant_disabled" should "flip the disabled flag" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val out = f.call(
      "set_tenant_disabled",
      McpSuperuserPat.principal,
      "name"     -> Json.fromString("acme"),
      "disabled" -> Json.True
    )
    out.isRight shouldBe true
    out.toOption.get.hcursor.get[Boolean]("disabled").toOption.get shouldBe true
  }

  "set_tenant_acl" should "set opaTokenSet without ever echoing the token" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val out = f.call(
      "set_tenant_acl",
      McpSuperuserPat.principal,
      "name"      -> Json.fromString("acme"),
      "mode"      -> Json.fromString("opa"),
      "opa_url"   -> Json.fromString("http://opa.local:8181"),
      "opa_token" -> Json.fromString("tok")
    )
    out.isRight shouldBe true
    out.toOption.get.hcursor.get[Boolean]("opaTokenSet").toOption.get shouldBe true
    out.toOption.get.noSpaces should not include "tok"
  }

  "set_tenant_auth" should "store client_secret write-only and report clientSecretSet" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    f.tools.tools
      .find(_.name == "set_tenant_auth")
      .get
      .inputSchema
      .hcursor
      .downField("properties")
      .downField("client_secret")
      .get[String]("type") shouldBe Right("string")
    val config = Json.obj(
      "issuerUrl" -> Json.fromString("https://idp.example.com/realms/acme"),
      "clientId"  -> Json.fromString("qod")
    )
    def setAuth(secret: String) = f.call(
      "set_tenant_auth",
      adminPat(),
      "name"          -> Json.fromString("acme"),
      "auth_provider" -> Json.fromString("keycloak"),
      "auth_config"   -> config,
      "client_secret" -> Json.fromString(secret)
    )
    val set = setAuth("kc-s3cr3t")
    set.isRight shouldBe true
    set.toOption.get.hcursor.get[Boolean]("clientSecretSet") shouldBe Right(true)
    set.toOption.get.noSpaces should not include "kc-s3cr3t"
    f.store.listTenants().find(_.id == "acme").flatMap(_.authClientSecret) shouldBe
      Some("kc-s3cr3t")
    setAuth("").toOption.get.hcursor.get[Boolean]("clientSecretSet") shouldBe Right(false)
  }

  it should "refuse a tenant admin PAT adding clientSecretRef, storing nothing" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val out = f.call(
      "set_tenant_auth",
      adminPat(),
      "name"          -> Json.fromString("acme"),
      "auth_provider" -> Json.fromString("keycloak"),
      "auth_config"   -> Json.obj(
        "clientSecretRef" -> Json.fromString("env:AWS_SECRET_ACCESS_KEY")
      )
    )
    out.left.toOption.get should startWith("superuser_required")
    f.store.listTenants().find(_.id == "acme").map(_.authProvider) shouldBe Some("db")
  }

  "create_user" should "create a tenant user and list it" in {
    val f       = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    created.isRight shouldBe true
    val listed = f.call(
      "list_users",
      McpSuperuserPat.principal,
      "tenant" -> Json.fromString("acme")
    )
    listed.toOption.get.hcursor.downField("users").values.get.size shouldBe 1
  }

  it should "let a tenant-scoped PAT create a user in its own tenant with an explicit tenant arg" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val out = f.call(
      "create_user",
      adminPat(),
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("carol"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    out.isRight shouldBe true
  }

  it should "pass roles and groups arrays through, refusing an empty roles list" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val out = f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret"),
      "roles"    -> Json.arr()
    )
    out.isLeft shouldBe true
    out.left.toOption.get should startWith("roles_required")
    val bad = f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret"),
      "groups"   -> Json.arr(Json.fromString("nope"))
    )
    bad.left.toOption.get should startWith("unknown_group")
  }

  it should "refuse a malformed roles or groups argument and create nothing" in {
    val f                            = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    def attempt(arg: (String, Json)) =
      f.call(
        "create_user",
        McpSuperuserPat.principal,
        "tenant"   -> Json.fromString("acme"),
        "username" -> Json.fromString("bob"),
        "password" -> Json.fromString("s3cret-s3cret"),
        arg
      )
    attempt("roles" -> Json.fromString("analyst")).left.toOption.get shouldBe
      "the 'roles' argument must be an array of role names"
    attempt("roles" -> Json.arr(Json.fromString("a"), Json.fromInt(5))).isLeft shouldBe true
    attempt("groups" -> Json.arr(Json.fromString(" "))).left.toOption.get shouldBe
      "the 'groups' argument must be an array of group names"
    f.call("list_users", McpSuperuserPat.principal, "tenant" -> Json.fromString("acme"))
      .toOption
      .get
      .hcursor
      .downField("users")
      .values
      .get
      .size shouldBe 0
    attempt("roles" -> Json.Null).isRight shouldBe true
  }

  it should "refuse a PAT creating a superuser (no tenant arg)" in {
    val f   = new Fixture
    val out = f.call(
      "create_user",
      adminPat(),
      "username" -> Json.fromString("root2"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    out.isLeft shouldBe true
    out.left.toOption.get should include("tenant_forbidden")
  }

  "update_user" should "disable a user by id" in {
    val f       = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id = f.idOf(created)
    f.seedPasswordHash(Some("acme"), "bob")
    val out = f.call(
      "update_user",
      McpSuperuserPat.principal,
      "id"      -> Json.fromString(id),
      "enabled" -> Json.False
    )
    out.isRight shouldBe true
    out.toOption.get.hcursor.get[Boolean]("enabled").toOption.get shouldBe false
  }

  "delete_user" should "delete a user and surface handler guard errors verbatim" in {
    val f       = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id  = f.idOf(created)
    val out = f.call("delete_user", McpSuperuserPat.principal, "id" -> Json.fromString(id))
    out.isRight shouldBe true
    val missing = f.call("delete_user", McpSuperuserPat.principal, "id" -> Json.fromString(id))
    missing.isLeft shouldBe true
    missing.left.toOption.get should include("not_found")
  }

  "user_effective_permissions" should "return the effective set for a user" in {
    val f       = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id  = f.idOf(created)
    val out =
      f.call("user_effective_permissions", McpSuperuserPat.principal, "id" -> Json.fromString(id))
    out.isRight shouldBe true
    out.toOption.get.hcursor.downField("user").get[String]("username").toOption.get shouldBe "bob"
  }

  "create_group and create_role" should "create in a tenant and list back" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val g = f.call(
      "create_group",
      McpSuperuserPat.principal,
      "tenant" -> Json.fromString("acme"),
      "name"   -> Json.fromString("analysts")
    )
    g.isRight shouldBe true
    val r = f.call(
      "create_role",
      McpSuperuserPat.principal,
      "tenant" -> Json.fromString("acme"),
      "name"   -> Json.fromString("reader")
    )
    r.isRight shouldBe true
    f.call("list_groups", McpSuperuserPat.principal, "tenant" -> Json.fromString("acme"))
      .toOption
      .get
      .hcursor
      .downField("groups")
      .values
      .get
      .filterNot(isBuiltin)
      .size shouldBe 1
    // create_tenant also seeds the built-in roles and groups (BuiltinRbac); count user-made ones.
    f.call("list_roles", McpSuperuserPat.principal, "tenant" -> Json.fromString("acme"))
      .toOption
      .get
      .hcursor
      .downField("roles")
      .values
      .get
      .filterNot(isBuiltin)
      .size shouldBe 1
  }

  "list_roles" should "infer the tenant for a tenant-scoped PAT" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    f.call(
      "create_role",
      McpSuperuserPat.principal,
      "tenant" -> Json.fromString("acme"),
      "name"   -> Json.fromString("reader")
    )
    val out = f.call("list_roles", adminPat()) // no tenant arg
    out.isRight shouldBe true
    // Only the "reader" role created above; create_tenant's built-ins are left out.
    out.toOption.get.hcursor.downField("roles").values.get.filterNot(isBuiltin).size shouldBe 1
  }

  "add_membership" should "attach a user to a role and reflect in effective permissions" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val u = f.idOf(
      f.call(
        "create_user",
        McpSuperuserPat.principal,
        "tenant"   -> Json.fromString("acme"),
        "username" -> Json.fromString("bob"),
        "password" -> Json.fromString("s3cret-s3cret"),
        "roles"    -> Json.arr(Json.fromString("qod_no_tables")),
        "groups"   -> Json.arr(Json.fromString("qod_no_pools"))
      )
    )
    val r = f.idOf(
      f.call(
        "create_role",
        McpSuperuserPat.principal,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("reader")
      )
    )
    val add = f.call(
      "add_membership",
      McpSuperuserPat.principal,
      "kind"    -> Json.fromString("user_role"),
      "user_id" -> Json.fromString(u),
      "role_id" -> Json.fromString(r)
    )
    add.isRight shouldBe true
    val eff =
      f.call("user_effective_permissions", McpSuperuserPat.principal, "id" -> Json.fromString(u))
    eff.toOption.get.hcursor
      .downField("roles")
      .values
      .get
      .flatMap(_.hcursor.get[String]("name").toOption)
      .toSet shouldBe Set("qod_no_tables", "reader")
  }

  it should "reject an unknown kind" in {
    val f   = new Fixture
    val out = f.call(
      "add_membership",
      McpSuperuserPat.principal,
      "kind"    -> Json.fromString("user_planet"),
      "user_id" -> Json.fromString("u"),
      "role_id" -> Json.fromString("r")
    )
    out.isLeft shouldBe true
    out.left.toOption.get should include("kind")
  }

  "remove_membership" should "detach a group role and list_group_role_memberships shows it" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val g = f.idOf(
      f.call(
        "create_group",
        McpSuperuserPat.principal,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("analysts")
      )
    )
    val r = f.idOf(
      f.call(
        "create_role",
        McpSuperuserPat.principal,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("reader")
      )
    )
    f.call(
      "add_membership",
      McpSuperuserPat.principal,
      "kind"     -> Json.fromString("group_role"),
      "group_id" -> Json.fromString(g),
      "role_id"  -> Json.fromString(r)
    ).isRight shouldBe true
    f.call(
      "list_group_role_memberships",
      McpSuperuserPat.principal,
      "group_id" -> Json.fromString(g)
    ).toOption
      .get
      .hcursor
      .downField("roles")
      .values
      .get
      .size shouldBe 1
    f.call(
      "remove_membership",
      McpSuperuserPat.principal,
      "kind"     -> Json.fromString("group_role"),
      "group_id" -> Json.fromString(g),
      "role_id"  -> Json.fromString(r)
    ).isRight shouldBe true
    f.call(
      "list_group_role_memberships",
      McpSuperuserPat.principal,
      "group_id" -> Json.fromString(g)
    ).toOption
      .get
      .hcursor
      .downField("roles")
      .values
      .get
      .size shouldBe 0
  }

  "delete_group and delete_role" should "delete by id" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val g = f.idOf(
      f.call(
        "create_group",
        McpSuperuserPat.principal,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("analysts")
      )
    )
    val r = f.idOf(
      f.call(
        "create_role",
        McpSuperuserPat.principal,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("reader")
      )
    )
    f.call("delete_group", McpSuperuserPat.principal, "id" -> Json.fromString(g))
      .isRight shouldBe true
    f.call("delete_role", McpSuperuserPat.principal, "id" -> Json.fromString(r))
      .isRight shouldBe true
  }

  "tenant scoping" should "hide other tenants' users from a tenant-scoped PAT" in {
    val f = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("umbrella"))
    f.call(
      "create_user",
      McpSuperuserPat.principal,
      "tenant"   -> Json.fromString("umbrella"),
      "username" -> Json.fromString("eve"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val listed = f.call(
      "list_users",
      adminPat(), // scoped to acme
      "tenant" -> Json.fromString("umbrella")
    )
    listed.isRight shouldBe true
    listed.toOption.get.hcursor.downField("users").values.get.size shouldBe 0
  }

  it should "refuse a tenant-scoped PAT updating another tenant's user" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("umbrella"))
    val eve = f.idOf(
      f.call(
        "create_user",
        McpSuperuserPat.principal,
        "tenant"   -> Json.fromString("umbrella"),
        "username" -> Json.fromString("eve"),
        "password" -> Json.fromString("s3cret-s3cret")
      )
    )
    val out = f.call(
      "update_user",
      adminPat(), // scoped to acme
      "id"      -> Json.fromString(eve),
      "enabled" -> Json.False
    )
    out.isLeft shouldBe true
  }

  "add_tenant_location" should "authorize a folder for its tenant's admin, and refuse an s3 location" in {
    val f     = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("acme"))
    val added = f.call(
      "add_tenant_location",
      adminPat(),
      "tenant"   -> Json.fromString("acme"),
      "location" -> Json.fromString("/srv/qod/acme/in/")
    )
    added.isRight shouldBe true
    val listed = f.call("list_tenant_locations", adminPat(), "tenant" -> Json.fromString("acme"))
    listed.toOption.get.noSpaces should include("/srv/qod/acme/in/")
    val id = added.toOption.get.hcursor.downField("location").get[String]("id").toOption.get
    f.call(
      "remove_tenant_location",
      adminPat(),
      "tenant" -> Json.fromString("acme"),
      "id"     -> Json.fromString(id)
    ).isRight shouldBe true
    f.call(
      "add_tenant_location",
      adminPat(),
      "tenant"   -> Json.fromString("acme"),
      "location" -> Json.fromString("s3://acme-etl/")
    ).left
      .toOption
      .get should include("invalid_location")
  }

  it should "refuse an admin of another tenant" in {
    val f   = new Fixture
    f.call("create_tenant", McpSuperuserPat.principal, "id" -> Json.fromString("globex"))
    val out = f.call(
      "add_tenant_location",
      adminPat(),
      "tenant"   -> Json.fromString("globex"),
      "location" -> Json.fromString("/srv/qod/globex/")
    )
    out.left.toOption.get should include("tenant_forbidden")
    f.call("list_tenant_locations", adminPat(), "tenant" -> Json.fromString("globex"))
      .left
      .toOption
      .get should include("tenant_forbidden")
  }
