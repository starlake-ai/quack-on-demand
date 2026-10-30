package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{PoolKey, Tenant, TenantAcl}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

class AuthzDeciderSpec extends AnyFlatSpec with Matchers:

  private val Pool    = PoolKey("acme", "acme_lake", "bi")
  private val Branch  = PoolKey("acme", "acme_lake", "__br_0a1b2c3d")
  private val OpaPool = PoolKey("opaco", "opaco_lake", "bi")
  // A kind=memory database (its node's catalog is DuckDB's `memory`) and a duckdb-file one
  // attached under a custom dbName.
  private val MemPool  = PoolKey("acme", "acme_mem", "bi")
  private val FilePool = PoolKey("acme", "acme_files", "bi")

  private def perm(id: String, cat: String, sch: String, tab: String, verb: String) =
    RolePermission(id, "r-1", cat, sch, tab, verb)

  private val AlicePerms = List(
    perm("p-1", "acme_lake", "main", "orders", "RO"),
    perm("p-2", "acme_lake", "main", "sales", "RW"),
    perm("p-3", "acme_lake", "staging", "*", "DDL"),
    perm("p-4", "acme_lake", "main", "v_secure", "RO")
  )

  private def eff(perms: List[RolePermission] = AlicePerms, tenant: String = "acme") =
    EffectiveSet(
      RbacUser("u-1", Some(tenant), "alice", "user"),
      List(RbacRole("r-1", tenant, "analyst")),
      Nil,
      perms,
      Nil
    )

  private def principal(
      e: EffectiveSet = eff(),
      pool: PoolKey = Pool,
      restriction: Option[TokenRestriction] = None
  ) = DecidePrincipal(
    NodePrincipal(e.user.id, e.user.username, e.user.tenant, superuser = false, system = false),
    Some(DecideContext(e, pool, restriction, DecidePurpose.Live))
  )

  private val Rules = LocationRules(
    s3Scopes = List("s3://acme-etl/"),
    tenantFolders = List("/srv/qod-files/acme/in/"),
    dataFolders = List("s3://acme-etl/lake/"),
    filesRoot = Some("/srv/qod-files/")
  )

  private val Classes: Map[String, FunctionClass] = Map(
    "lower"           -> FunctionClass.Allowed,
    "duckdb_tables"   -> FunctionClass.Listing,
    "read_parquet"    -> FunctionClass.File,
    "read_csv"        -> FunctionClass.File,
    "duckdb_settings" -> FunctionClass.Superuser
  )

  private val Settings: Map[String, SettingClass] =
    Map("timezone" -> SettingClass.Session, "s3_access_key_id" -> SettingClass.Superuser)

  private final class Fixture(
      rules: Either[String, LocationRules] = Right(Rules),
      opaReply: Either[String, (Int, String)] = Right((200, """{"result":{"allow":true}}""")),
      opaTtlSec: Int = 5,
      rulesThrow: Boolean = false
  ):
    val rulesCalls = new AtomicInteger(0)
    val rulesUsers = ListBuffer.empty[String]
    val opaBodies  = ListBuffer.empty[String]
    val opaCfg     = OpaConfig("qod", "", 500, opaTtlSec)
    val decider    = new AuthzDecider(
      tenantOf = Map(
        "acme"  -> Tenant("acme"),
        "opaco" -> Tenant("opaco", acl = TenantAcl(Some("opa"), Some("http://opa")))
      ).get,
      tenantCatalogs = Map("acme" -> Set("acme_lake"), "opaco" -> Set("opaco_lake"))
        .withDefaultValue(Set.empty[String]),
      poolCatalog = Map(
        Pool     -> "acme_lake",
        Branch   -> "acme_lake",
        MemPool  -> "memory",
        FilePool -> "acme_custom",
        OpaPool  -> "opaco_lake"
      ).get,
      parentPoolsOf = _ => Nil,
      locations = new LocationRulesSource {
        def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
          rulesCalls.incrementAndGet()
          rulesUsers += userId
          if rulesThrow then throw new IllegalStateException("control plane down")
          rules
      },
      opa = new OpaAuthorizer(
        opaCfg,
        new OpaClient(
          500,
          (_, body, _) => {
            opaBodies += body
            opaReply
          }
        ),
        new OpaDecisionCache(0),
        OpaInstruments.noop
      ),
      opaCfg = opaCfg,
      functionClass = (_, _, name, _) => Classes.get(name),
      settingClass = Settings.get
    )

  private def obj(
      name: String,
      access: String = "read",
      kind: String = "table",
      catalog: String = "acme_lake",
      schema: String = "main",
      via: Option[String] = None,
      columns: List[String] = Nil
  ) = DescribedObject(kind, access, catalog, "ducklake", schema, name, columns, via)

  private def fn(
      name: String,
      args: List[Option[String]] = Nil,
      tpe: String = "table",
      via: Option[String] = None
  ) = DescribedFunction("system", "main", name, tpe, args, via)

  private def plan(
      objects: List[DescribedObject] = Nil,
      functions: List[DescribedFunction] = Nil,
      settings: List[DescribedSetting] = Nil,
      control: List[DescribedControl] = Nil
  ) = Description("PROJECTION", objects, functions, settings, control)

  private def ctl(op: String, target: Option[String] = None) = DescribedControl(op, target, None)

  private def allow(d: Description, p: DecidePrincipal = principal(), f: Fixture = new Fixture()) =
    withClue(d)(f.decider.decidePlan(p, d) shouldBe Decision.Allow)

  private def refused(
      d: Description,
      reason: String,
      p: DecidePrincipal = principal(),
      f: Fixture = new Fixture()
  ) = f.decider.decidePlan(p, d) shouldBe Decision.Refuse(reason)

  // ---- rule 1: the principal ----

  "a superuser or the system principal" should "be allowed whatever the statement" in {
    val f    = new Fixture()
    val d    = plan(control = List(ctl("attach")), functions = List(fn("unclassified_fn")))
    val root = NodePrincipal("root", "root", None, superuser = true, system = false)
    f.decider.decidePlan(DecidePrincipal(root, None), d) shouldBe Decision.Allow
    f.decider.decidePlan(DecidePrincipal(NodePrincipal.System, None), d) shouldBe Decision.Allow
    f.decider.decideScreen(
      DecidePrincipal(root, None),
      ScreenDescription(List(DescribedLocation("read", "/etc/passwd", "/etc/passwd", "read_csv")))
    ) shouldBe Decision.Allow
  }

  "a user without a minted context" should "be unavailable, retryably" in {
    val f = new Fixture()
    val p = principal().copy(context = None)
    f.decider.decidePlan(p, plan()) shouldBe Decision.Unavailable(AuthzDecider.ContextExpired)
    f.decider.decideScreen(p, ScreenDescription(Nil)) shouldBe
      Decision.Unavailable(AuthzDecider.ContextExpired)
  }

  // ---- rule 3: objects by QoD grants ----

  "an object" should "be allowed by a covering grant and refused without one" in {
    allow(plan(objects = List(obj("orders", columns = List("id", "v")))))
    refused(
      plan(objects = List(obj("customers"))),
      "user:alice lacks grants on acme_lake.main.customers:read"
    )
  }

  it should "need RW to insert, update or delete" in {
    List("insert", "update", "delete").foreach(a => allow(plan(objects = List(obj("sales", a)))))
    refused(
      plan(objects = List(obj("orders", "insert"), obj("orders", "update"))),
      "user:alice lacks grants on acme_lake.main.orders:insert, acme_lake.main.orders:update"
    )
  }

  it should "need DDL to create, drop or alter" in {
    allow(
      plan(objects =
        List(obj("t", "create", schema = "staging"), obj("t", "alter", schema = "staging"))
      )
    )
    refused(
      plan(objects = List(obj("orders", "drop"))),
      "user:alice lacks grants on acme_lake.main.orders:drop"
    )
  }

  it should "match a schema's DDL against a schema-wide grant" in {
    allow(plan(objects = List(obj("", "create", kind = "schema", schema = "staging"))))
    refused(
      plan(objects = List(obj("", "create", kind = "schema", schema = "other"))),
      "user:alice lacks grants on acme_lake.other:create"
    )
  }

  it should "scope a wildcard catalog grant to the tenant's own catalogs" in {
    val wide = principal(eff(List(perm("p-9", "*", "*", "*", "RO"))))
    allow(plan(objects = List(obj("x"))), wide)
    refused(
      plan(objects = List(obj("x", catalog = "globex_lake"))),
      "user:alice lacks grants on globex_lake.main.x:read",
      wide
    )
  }

  it should "be covered through a secure view by a read grant on the view" in {
    allow(
      plan(objects =
        List(
          obj("v_secure", kind = "secure_view"),
          obj("base", via = Some("acme_lake.main.v_secure"))
        )
      )
    )
    refused(
      plan(objects =
        List(
          obj("v_other", kind = "secure_view"),
          obj("base", via = Some("acme_lake.main.v_other"))
        )
      ),
      "user:alice lacks grants on acme_lake.main.base:read, acme_lake.main.v_other:read"
    )
    // A view name of fewer than three non-empty parts covers nothing.
    refused(
      plan(objects =
        List(
          obj("v_secure", kind = "secure_view", schema = ""),
          obj("base", via = Some("acme_lake..v_secure"))
        )
      ),
      "user:alice lacks grants on acme_lake..v_secure:read, acme_lake.main.base:read"
    )
  }

  it should "be refused when its secure view is not read by the description (defense in depth)" in {
    refused(
      plan(objects = List(obj("base", via = Some("acme_lake.main.v_secure")))),
      "the description reads through secure view acme_lake.main.v_secure without reading it"
    )
    refused(
      plan(objects = List(obj("base", via = Some("v_secure")))),
      "the description reads through secure view v_secure without reading it"
    )
    refused(
      plan(functions =
        List(fn("read_csv", List(Some("/var/loose/x.csv")), via = Some("acme_lake.main.v_secure")))
      ),
      "the description reads through secure view acme_lake.main.v_secure without reading it"
    )
    // The view read under another case still counts: DuckDB names are case-insensitive.
    allow(
      plan(objects =
        List(
          obj("V_Secure", kind = "secure_view"),
          obj("base", via = Some("acme_lake.main.v_secure"))
        )
      )
    )
  }

  "a wildcard catalog grant" should "cover the catalog the pool's node attaches, on that pool only" in {
    // qod_all_tables: *.*.* ALL
    val all = eff(List(perm("p-9", "*", "*", "*", "ALL")))
    allow(plan(objects = List(obj("v", catalog = "memory"))), principal(all, MemPool))
    refused(
      plan(objects = List(obj("x", catalog = "memory"))),
      "user:alice lacks grants on memory.main.x:read",
      principal(all, Pool)
    )
    allow(plan(objects = List(obj("t", catalog = "acme_custom"))), principal(all, FilePool))
    refused(
      plan(objects = List(obj("t", catalog = "acme_custom"))),
      "user:alice lacks grants on acme_custom.main.t:read",
      principal(all, Pool)
    )
    // A principal of another tenant on the pool: its wildcard never reaches the pool's catalog.
    refused(
      plan(objects = List(obj("v", catalog = "memory"))),
      "user:alice lacks grants on memory.main.v:read",
      principal(eff(List(perm("p-9", "*", "*", "*", "ALL")), tenant = "globex"), MemPool)
    )
  }

  "attachedCatalog" should "name memory for kind memory, else the catalogAlias or dbName" in {
    AuthzDecider.attachedCatalog("memory", Map("dbName" -> "memory")) shouldBe Some("memory")
    AuthzDecider.attachedCatalog("memory", Map("dbName" -> "custom")) shouldBe Some("memory")
    AuthzDecider.attachedCatalog("duckdb-file", Map("dbName" -> "acme_custom")) shouldBe
      Some("acme_custom")
    AuthzDecider.attachedCatalog(
      "ducklake",
      Map("dbName" -> "acme_lake", "catalogAlias" -> "lake")
    ) shouldBe Some("lake")
    AuthzDecider.attachedCatalog("ducklake", Map.empty) shouldBe None
    AuthzDecider.attachedCatalog("iceberg", Map("dbName" -> "x")) shouldBe None
  }

  it should "be covered through nested secure views by a read grant on the outermost one" in {
    def chain(outer: String) = List(
      obj(outer, kind = "secure_view"),
      obj("v_inner", kind = "secure_view", via = Some(s"acme_lake.main.$outer")),
      obj("base", via = Some("acme_lake.main.v_inner"))
    )
    allow(plan(objects = chain("v_secure")))
    refused(
      plan(objects = chain("v_open")),
      "user:alice lacks grants on acme_lake.main.base:read, acme_lake.main.v_inner:read, " +
        "acme_lake.main.v_open:read"
    )
    val cycle = List(
      obj("a", kind = "secure_view", via = Some("acme_lake.main.b")),
      obj("b", kind = "secure_view", via = Some("acme_lake.main.a"))
    )
    refused(
      plan(objects = cycle),
      "user:alice lacks grants on acme_lake.main.a:read, acme_lake.main.b:read"
    )
  }

  it should "be narrowed by the token's restriction" in {
    val noRoles = TokenRestriction.Unrestricted.copy(roles = Some(Set.empty))
    refused(
      plan(objects = List(obj("orders"))),
      "user:alice lacks grants on acme_lake.main.orders:read",
      principal(restriction = Some(noRoles))
    )
    val readOnly = TokenRestriction.Unrestricted.copy(verbCeiling = Some("RO"))
    allow(plan(objects = List(obj("sales"))), principal(restriction = Some(readOnly)))
    refused(
      plan(objects = List(obj("sales", "insert"))),
      "user:alice lacks grants on acme_lake.main.sales:insert",
      principal(restriction = Some(readOnly))
    )
  }

  // ---- rule 2: always-on ----

  "the temp catalog" should "need no grant, but its macros stay superuser-only" in {
    allow(
      plan(objects =
        List(obj("scratch", "create", catalog = "temp"), obj("scratch", "insert", catalog = "temp"))
      )
    )
    refused(
      plan(objects = List(obj("m", "create", kind = "macro", catalog = "temp"))),
      "create macro temp.main.m is superuser-only"
    )
  }

  "the system catalog" should "be readable without a grant and never writable" in {
    allow(plan(objects = List(obj("duckdb_tables", kind = "view", catalog = "system"))))
    refused(
      plan(objects = List(obj("x", "insert", catalog = "system"))),
      "insert in the system catalog is superuser-only"
    )
  }

  "DuckLake catalog metadata" should "be refused even under *.*.* ALL" in {
    val all = principal(eff(List(perm("p-9", "*", "*", "*", "ALL"))))
    refused(
      plan(objects = List(obj("ducklake_snapshot", catalog = "__ducklake_metadata_acme_lake"))),
      "__ducklake_metadata_acme_lake.main.ducklake_snapshot is DuckLake catalog metadata, which " +
        "only superusers reach",
      all
    )
  }

  "macros, triggers and secure views" should "be created, altered or dropped by superusers only" in {
    val all = principal(eff(List(perm("p-9", "*", "*", "*", "ALL"))))
    refused(
      plan(objects = List(obj("v", "create", kind = "secure_view"))),
      "create secure view acme_lake.main.v is superuser-only",
      all
    )
    refused(
      plan(objects = List(obj("tr", "drop", kind = "trigger"))),
      "drop trigger acme_lake.main.tr is superuser-only",
      all
    )
    refused(
      plan(objects = List(obj("m", "alter", kind = "macro"))),
      "alter macro acme_lake.main.m is superuser-only",
      all
    )
    allow(plan(objects = List(obj("v_secure", kind = "secure_view"))))
  }

  "a secure view" should "only ever be read through" in
    refused(
      plan(objects = List(obj("base", "insert", via = Some("acme_lake.main.v_secure")))),
      "acme_lake.main.base is reached through secure view acme_lake.main.v_secure by a insert"
    )

  "an unknown object kind or access" should "be refused" in {
    refused(
      plan(objects = List(obj("x", kind = "dictionary"))),
      "the description names an unknown object kind 'dictionary'"
    )
    refused(
      plan(objects = List(obj("x", access = "truncate"))),
      "the description names an unknown access 'truncate'"
    )
  }

  "a function" should "be allowed when classified allowed or listing" in
    allow(plan(functions = List(fn("lower", tpe = "scalar"), fn("duckdb_tables"))))

  it should "be refused when superuser-only or unclassified" in {
    refused(
      plan(functions = List(fn("duckdb_settings"))),
      "function duckdb_settings is superuser-only"
    )
    refused(
      plan(functions = List(fn("evil_fn", tpe = "scalar"))),
      "function evil_fn is not classified"
    )
    refused(
      plan(functions = List(fn("lower", tpe = "macro"))),
      "the description names an unknown function type 'macro'"
    )
  }

  it should "run with the view owner's rights through a secure view, but still be classified" in {
    val view = obj("v_secure", kind = "secure_view")
    allow(
      plan(
        objects = List(view),
        functions = List(fn("duckdb_settings", via = Some("acme_lake.main.v_secure")))
      )
    )
    allow(
      plan(
        objects = List(view),
        functions = List(
          fn("read_csv", List(Some("/var/loose/x.csv")), via = Some("acme_lake.main.v_secure"))
        )
      )
    )
    refused(
      plan(functions = List(fn("evil_fn", via = Some("acme_lake.main.v_secure")))),
      "function evil_fn is not classified"
    )
  }

  // ---- rule 4: file functions and COPY TO ----

  "a file function" should "be allowed on an authorized location and refused elsewhere" in {
    allow(plan(functions = List(fn("read_parquet", List(Some("s3://acme-etl/in/x.parquet"))))))
    allow(
      plan(functions =
        List(
          fn("read_csv", List(Some("/srv/qod-files/acme/in/a.csv"), Some("s3://acme-etl/in/b.csv")))
        )
      )
    )
    refused(
      plan(functions = List(fn("read_parquet", List(Some("s3://acme-etl/lake/main/t/f.parquet"))))),
      "s3://acme-etl/lake/main/t/f.parquet is a database data folder"
    )
    refused(
      plan(functions = List(fn("read_parquet", List(Some("s3://other/x.parquet"))))),
      "s3://other/x.parquet is outside the tenant's authorized locations " +
        "(no S3 credential of yours covers it)"
    )
    refused(
      plan(functions = List(fn("read_csv", List(Some("/etc/passwd"))))),
      "/etc/passwd is outside the tenant's authorized locations"
    )
  }

  it should "admit an s3 location only under the principal's own credential scopes (decision 23)" in {
    val none = new Fixture(rules = Right(Rules.copy(s3Scopes = Nil)))
    none.decider.decidePlan(
      principal(),
      plan(functions = List(fn("read_parquet", List(Some("s3://acme-etl/in/x.parquet")))))
    ) shouldBe Decision.Refuse(
      "s3://acme-etl/in/x.parquet is outside the tenant's authorized locations " +
        "(no S3 credential of yours covers it)"
    )
    none.decider.decidePlan(
      principal(),
      plan(functions = List(fn("read_csv", List(Some("/srv/qod-files/acme/in/a.csv")))))
    ) shouldBe Decision.Allow
    // The rules are asked for the handle's principal, whose own scopes they hold.
    none.rulesUsers.toList.distinct shouldBe List("u-1")
  }

  it should "be refused without a literal location" in {
    refused(plan(functions = List(fn("read_parquet"))), "function read_parquet names no location")
    // A COPY FROM's reader, bound without a catalog: re-checked on the path the describer carries
    // in its args (Task 9), never passed on the parse screen's earlier judgment.
    refused(
      plan(functions = List(DescribedFunction("", "", "read_csv", "table", Nil, None))),
      "function read_csv names no location"
    )
    allow(
      plan(functions =
        List(
          DescribedFunction("", "", "read_csv", "table", List(Some("s3://acme-etl/in/x.csv")), None)
        )
      )
    )
    refused(
      plan(functions =
        List(
          DescribedFunction(
            "",
            "",
            "read_csv",
            "table",
            List(Some("s3://acme-etl/lake/x.csv")),
            None
          )
        )
      ),
      "s3://acme-etl/lake/x.csv is a database data folder"
    )
    refused(
      plan(functions = List(DescribedFunction("", "", "evil_fn", "table", Nil, None))),
      "function evil_fn is not classified"
    )
    refused(
      plan(functions = List(fn("read_parquet", List(Some("s3://acme-etl/in/x.parquet"), None)))),
      "function read_parquet names a location that is not a literal"
    )
  }

  it should "be unavailable while the location rules cannot be read" in {
    val f = new Fixture(rules = Left("the database data folders could not be read"))
    f.decider.decidePlan(
      principal(),
      plan(functions = List(fn("read_parquet", List(Some("s3://acme-etl/in/x.parquet")))))
    ) shouldBe Decision.Unavailable("the database data folders could not be read")
  }

  it should "be unavailable when the rules throw, for a plan and a screen" in {
    val f   = new Fixture(rulesThrow = true)
    val loc = "s3://acme-etl/in/x.parquet"
    f.decider.decidePlan(
      principal(),
      plan(functions = List(fn("read_parquet", List(Some(loc)))))
    ) shouldBe
      Decision.Unavailable(AuthzDecider.RulesUnavailable)
    f.decider.decidePlan(principal(), plan(control = List(ctl("copy_to", Some(loc))))) shouldBe
      Decision.Unavailable(AuthzDecider.RulesUnavailable)
    f.decider.decideScreen(
      principal(),
      ScreenDescription(List(DescribedLocation("read", loc, loc, "read_parquet")))
    ) shouldBe Decision.Unavailable(AuthzDecider.RulesUnavailable)
  }

  it should "be unavailable while the user's S3 credentials cannot be served (a stale cache)" in {
    val stale = new Fixture(rules = Left("the S3 credentials could not be read"))
    stale.decider.decidePlan(
      principal(),
      plan(functions = List(fn("read_csv", List(Some("/srv/qod-files/acme/in/a.csv")))))
    ) shouldBe Decision.Unavailable("the S3 credentials could not be read")
    stale.decider.decideScreen(
      principal(),
      ScreenDescription(
        List(DescribedLocation("write", "s3://acme-etl/out/x", "s3://acme-etl/out/x", "copy_to"))
      )
    ) shouldBe Decision.Unavailable("the S3 credentials could not be read")
  }

  "the location rules" should "be read once, and only for a statement naming a location" in {
    val f = new Fixture()
    allow(plan(objects = List(obj("orders"))), f = f)
    f.rulesCalls.get() shouldBe 0
    allow(
      plan(
        functions = List(
          fn("read_parquet", List(Some("s3://acme-etl/in/a.parquet"))),
          fn("read_parquet", List(Some("s3://acme-etl/in/b.parquet")))
        ),
        control = List(ctl("copy_to", Some("s3://acme-etl/out/x.parquet")))
      ),
      f = f
    )
    f.rulesCalls.get() shouldBe 1
  }

  it should "refuse a location named by a principal of another tenant than the pool's" in {
    val stranger = principal(eff(tenant = "globex"))
    refused(
      plan(functions = List(fn("read_parquet", List(Some("s3://acme-etl/in/x.parquet"))))),
      "a principal of another tenant names locations on a pool of tenant 'acme'",
      stranger
    )
  }

  "COPY TO" should "be allowed into an authorized location only" in {
    allow(
      plan(
        objects = List(obj("orders")),
        control = List(ctl("copy_to", Some("s3://acme-etl/out/x.parquet")))
      )
    )
    refused(
      plan(control = List(ctl("copy_to", Some("s3://acme-etl/lake/x.parquet")))),
      "s3://acme-etl/lake/x.parquet is a database data folder"
    )
    refused(plan(control = List(ctl("copy_to"))), "COPY TO names no location")
  }

  // ---- settings and control ----

  "a setting" should "be allowed at session or local scope when classified session" in {
    allow(plan(settings = List(DescribedSetting("timezone", "session", Some("UTC")))))
    allow(plan(settings = List(DescribedSetting("timezone", "local", None))))
    allow(plan(settings = List(DescribedSetting("my_variable", "variable", None))))
  }

  it should "be refused at global scope, when superuser-only, unclassified or of no known scope" in {
    refused(
      plan(settings = List(DescribedSetting("timezone", "global", Some("UTC")))),
      "setting timezone at global scope is superuser-only"
    )
    refused(
      plan(settings = List(DescribedSetting("s3_access_key_id", "session", Some("x")))),
      "setting s3_access_key_id is superuser-only"
    )
    refused(
      plan(settings = List(DescribedSetting("new_setting", "session", None))),
      "setting new_setting is not classified"
    )
    refused(
      plan(settings = List(DescribedSetting("timezone", "instance", None))),
      "the description names an unknown setting scope 'instance'"
    )
  }

  "a control step" should "be allowed for transactions, prepared statements and EXPLAIN ANALYZE" in
    List("transaction", "prepare", "execute", "deallocate", "explain_analyze").foreach(op =>
      allow(plan(control = List(ctl(op))))
    )

  it should "be superuser-only otherwise, and refused when unknown" in {
    List(
      "attach",
      "detach",
      "load",
      "install",
      "create_secret",
      "drop_secret",
      "export",
      "copy_database",
      "pragma",
      "vacuum",
      "update_extensions",
      "checkpoint",
      "extension_operator"
    ).foreach(op =>
      refused(plan(control = List(ctl(op))), s"statement uses $op, which only superusers may run")
    )
    refused(
      plan(control = List(ctl("teleport"))),
      "the description names an unknown control op 'teleport'"
    )
  }

  // ---- rule 5: branchOnly ----

  "a branchOnly token" should "write only on a branch pool" in {
    val r = Some(TokenRestriction.Unrestricted.copy(branchOnly = true))
    refused(
      plan(objects = List(obj("sales", "insert"))),
      AuthzDecider.WriteRequiresBranch,
      principal(restriction = r)
    )
    refused(
      plan(control = List(ctl("copy_to", Some("s3://acme-etl/out/x.parquet")))),
      AuthzDecider.WriteRequiresBranch,
      principal(restriction = r)
    )
    allow(plan(objects = List(obj("sales", "insert"))), principal(pool = Branch, restriction = r))
    allow(plan(objects = List(obj("orders"))), principal(restriction = r))
    allow(
      plan(objects = List(obj("scratch", "create", catalog = "temp"))),
      principal(restriction = r)
    )
  }

  // ---- a token's verb ceiling on files ----

  private val ReadOnly = Some(TokenRestriction.Unrestricted.copy(verbCeiling = Some("RO")))

  "a read-only token" should "write no file, though the read and the target are allowed" in {
    val copy = plan(
      objects = List(obj("orders")),
      control = List(ctl("copy_to", Some("s3://acme-etl/out/x.parquet")))
    )
    allow(copy)
    refused(
      copy,
      "user:alice denied by token verb ceiling on file writes",
      principal(restriction = ReadOnly)
    )
    // An RW ceiling covers Write.
    allow(
      copy,
      principal(restriction = Some(TokenRestriction.Unrestricted.copy(verbCeiling = Some("RW"))))
    )
  }

  it should "write no file on an OPA tenant either, and never ask OPA" in {
    val f = new Fixture()
    f.decider.decidePlan(
      principal(OpaAlice.context.get.effective, OpaPool, ReadOnly),
      plan(control = List(ctl("copy_to", Some("s3://acme-etl/out/x.parquet"))))
    ) shouldBe Decision.Refuse("user:alice denied by token verb ceiling on file writes")
    f.opaBodies shouldBe empty
  }

  // ---- OPA tenants ----

  private val OpaAlice =
    principal(eff(List.empty, tenant = "opaco").copy(claims = Map("dept" -> "fin")), OpaPool)

  "an OPA tenant" should "be decided by its policy on the resolved accesses and locations" in {
    val f = new Fixture()
    f.decider.decidePlan(
      OpaAlice,
      plan(
        objects = List(obj("orders", catalog = "opaco_lake", columns = List("v", "id"))),
        functions = List(fn("read_parquet", List(Some("s3://acme-etl/in/x.parquet"))))
      )
    ) shouldBe Decision.Allow
    f.opaBodies should have size 1
    val input = parse(f.opaBodies.head).toOption.get.hcursor.downField("input")
    input.downField("client").get[String]("edge") shouldBe Right("node")
    input.downField("statement").get[String]("class") shouldBe Right("READ")
    input.downField("user").downField("claims").get[String]("dept") shouldBe Right("fin")
    val access = input.downField("accesses").downN(0)
    access.get[String]("table") shouldBe Right("orders")
    access.get[List[String]]("columns") shouldBe Right(List("id", "v"))
    access.get[Option[String]]("via") shouldBe Right(None)
    access.get[String]("kind") shouldBe Right("table")
    val location = input.downField("locations").downN(0)
    location.get[String]("location") shouldBe Right("s3://acme-etl/in/x.parquet")
    location.get[String]("scope") shouldBe Right("s3://acme-etl/")
    location.get[String]("access") shouldBe Right("read")
    location.get[String]("via") shouldBe Right("read_parquet")
  }

  it should "be refused when the policy denies, unavailable when OPA fails" in {
    val deny = new Fixture(opaReply = Right((200, """{"result":{"allow":false,"reason":"no"}}""")))
    deny.decider.decidePlan(
      OpaAlice,
      plan(objects = List(obj("orders", catalog = "opaco_lake")))
    ) shouldBe
      Decision.Refuse("user:alice denied by policy on opaco_lake.main.orders:read: no")
    val down = new Fixture(opaReply = Left("connection refused"))
    down.decider.decidePlan(
      OpaAlice,
      plan(objects = List(obj("orders", catalog = "opaco_lake")))
    ) shouldBe
      Decision.Unavailable(OpaAuthorizer.UnavailableMessage)
  }

  it should "not ask OPA about a statement a local rule refuses, or one with nothing to judge" in {
    val f = new Fixture()
    f.decider.decidePlan(OpaAlice, plan(control = List(ctl("attach")))) shouldBe
      Decision.Refuse("statement uses attach, which only superusers may run")
    f.decider.decidePlan(OpaAlice, plan(functions = List(fn("lower", tpe = "scalar")))) shouldBe
      Decision.Allow
    f.decider.decidePlan(
      OpaAlice,
      plan(objects = List(obj("scratch", "create", catalog = "temp")))
    ) shouldBe
      Decision.Allow
    f.opaBodies shouldBe empty
  }

  it should "refuse a read through a malformed secure view without asking OPA" in {
    val f = new Fixture()
    f.decider.decidePlan(
      OpaAlice,
      plan(objects =
        List(
          obj("v", kind = "secure_view", catalog = "opaco_lake", schema = ""),
          obj("base", catalog = "opaco_lake", via = Some("opaco_lake..v"))
        )
      )
    ) shouldBe Decision.Refuse("user:alice lacks grants on opaco_lake.main.base:read")
    f.opaBodies shouldBe empty
  }

  it should "enforce a token's verb ceiling before asking OPA" in {
    val f       = new Fixture()
    val ceiling =
      OpaAlice.context.get.effective.copy(verbCeiling = Some(Set(ai.starlake.acl.parser.Verb.Read)))
    f.decider.decidePlan(
      principal(ceiling, OpaPool),
      plan(objects = List(obj("orders", "insert", catalog = "opaco_lake")))
    ) shouldBe Decision.Refuse(
      "user:alice denied by token verb ceiling on opaco_lake.main.orders:insert"
    )
    f.opaBodies shouldBe empty
  }

  // ---- screen decisions ----

  private def screen(locs: DescribedLocation*) = ScreenDescription(locs.toList)
  private def loc(resolved: String, via: String = "read_parquet", access: String = "read") =
    DescribedLocation(access, resolved, resolved, via)

  "a screen" should "admit authorized locations of file functions and COPY" in {
    val f = new Fixture()
    f.decider.decideScreen(
      principal(),
      screen(
        loc("s3://acme-etl/in/*.parquet"),
        loc("/srv/qod-files/acme/in/x.csv", "copy_from"),
        loc("s3://acme-etl/out/x.parquet", "copy_to", "write")
      )
    ) shouldBe Decision.Allow
  }

  it should "refuse data folders, unauthorized locations and superuser-only statements" in {
    val f                            = new Fixture()
    def decide(l: DescribedLocation) = f.decider.decideScreen(principal(), screen(l))
    decide(loc("s3://acme-etl/lake/x.parquet")) shouldBe
      Decision.Refuse("s3://acme-etl/lake/x.parquet is a database data folder")
    decide(loc("/etc/passwd", "read_csv")) shouldBe
      Decision.Refuse("/etc/passwd is outside the tenant's authorized locations")
    List("attach", "import", "export", "load").foreach(v =>
      decide(loc("s3://acme-etl/in/x", v)) shouldBe
        Decision.Refuse(s"statement uses $v, which only superusers may run")
    )
    decide(loc("s3://acme-etl/in/x", "mystery_reader")) shouldBe
      Decision.Refuse("function mystery_reader is not classified")
    decide(loc("s3://acme-etl/in/x", "lower")) shouldBe
      Decision.Refuse("function lower is not a file function")
    decide(loc("s3://acme-etl/in/x", access = "append")) shouldBe
      Decision.Refuse("the description names an unknown location access 'append'")
  }

  it should "refuse a write location to a branchOnly token on a non-branch pool" in {
    val f = new Fixture()
    val r = Some(TokenRestriction.Unrestricted.copy(branchOnly = true))
    f.decider.decideScreen(
      principal(restriction = r),
      screen(loc("s3://acme-etl/out/x", "copy_to", "write"))
    ) shouldBe
      Decision.Refuse(AuthzDecider.WriteRequiresBranch)
    f.decider.decideScreen(
      principal(pool = Branch, restriction = r),
      screen(loc("s3://acme-etl/out/x", "copy_to", "write"))
    ) shouldBe
      Decision.Allow
  }

  it should "refuse a write location to a read-only token, and keep its reads" in {
    val f  = new Fixture()
    val ro = Some(TokenRestriction.Unrestricted.copy(verbCeiling = Some("RO")))
    f.decider.decideScreen(
      principal(restriction = ro),
      screen(loc("s3://acme-etl/out/x", "copy_to", "write"))
    ) shouldBe Decision.Refuse("user:alice denied by token verb ceiling on file writes")
    f.decider.decideScreen(
      principal(OpaAlice.context.get.effective, OpaPool, ro),
      screen(loc("/srv/qod-files/acme/in/x.csv", "read_csv", "write"))
    ) shouldBe Decision.Refuse("user:alice denied by token verb ceiling on file writes")
    f.decider.decideScreen(
      principal(restriction = ro),
      screen(loc("s3://acme-etl/in/x.parquet"), loc("/srv/qod-files/acme/in/x.csv", "copy_from"))
    ) shouldBe Decision.Allow
  }

  it should "be unavailable while the rules cannot be read, and never ask OPA" in {
    val down = new Fixture(rules = Left("the database data folders could not be read"))
    down.decider.decideScreen(principal(), screen(loc("s3://acme-etl/in/x"))) shouldBe
      Decision.Unavailable("the database data folders could not be read")
    val f = new Fixture()
    f.decider.decideScreen(
      principal(eff(List.empty, tenant = "opaco"), OpaPool),
      screen(loc("s3://acme-etl/in/x"))
    ) shouldBe
      Decision.Allow
    f.opaBodies shouldBe empty
  }

  // ---- verdict lifetime ----

  "lifetimeMs" should "be 60 s for QoD grants and the OPA TTL, at least 5 s, for OPA tenants" in {
    new Fixture().decider.lifetimeMs(principal()) shouldBe 60_000L
    new Fixture(opaTtlSec = 30).decider.lifetimeMs(OpaAlice) shouldBe 30_000L
    new Fixture(opaTtlSec = 0).decider.lifetimeMs(OpaAlice) shouldBe 5_000L
    new Fixture().decider.lifetimeMs(DecidePrincipal(NodePrincipal.System, None)) shouldBe 60_000L
  }
