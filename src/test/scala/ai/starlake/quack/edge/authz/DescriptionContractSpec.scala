package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.authz.location.{DataFolders, LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{PoolKey, Tenant, TenantDbKind}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** The node and the manager cannot drift: every golden description qod-node's describer_test pins
  * (native/qod-node/tests/golden/descriptions) is canonical by Scala's CanonicalJson too, and
  * decodes and decides here as the table below says, through the real decider and the committed
  * function and setting classification. A golden without an expectation fails the spec.
  */
class DescriptionContractSpec extends AnyFlatSpec with Matchers:

  private val Dir    = Paths.get("native/qod-node/tests/golden/descriptions")
  private val Root   = "/srv/qod-files/"
  private val Folder = "/srv/qod-files/acme/work"

  /** A kind=memory pool: its node's catalog is DuckDB's `memory`, the goldens' database. */
  private val Pool = PoolKey("acme", "acme_mem", "bi")

  /** A DuckLake pool of the same tenant, whose node attaches `acme_lake`, not `memory`. */
  private val LakePool = PoolKey("acme", "acme_lake", "bi")

  /** Every `<case>.json` golden: its name and its bytes without the one trailing newline. */
  private lazy val goldens: Map[String, String] =
    val files = Files.list(Dir)
    try
      files.iterator.asScala
        .filter(_.getFileName.toString.endsWith(".json"))
        .map(p =>
          p.getFileName.toString
            .stripSuffix(".json") -> Files.readString(p, UTF_8).stripSuffix("\n")
        )
        .toMap
    finally files.close()

  private enum Expected:
    case Allow

    /** Refused, for a reason that holds `why`. */
    case Refuse(why: String)

  import Expected.*

  private def lacks(on: String)         = Refuse(s"user:alice lacks grants on $on")
  private def superuserOnly(op: String) = Refuse(
    s"statement uses $op, which only superusers may run"
  )

  /** The decision for alice: RW on the tables t, c, chk, kv and dflt, RO on the secure views below
    * except sv_fn; nothing on u, pk_parent, pv, seq1 or any other object, and no DDL.
    */
  private val expected: Map[String, Expected] = Map(
    // control
    "control_attach"                 -> superuserOnly("attach"),
    "control_begin"                  -> Allow,
    "control_checkpoint"             -> Refuse("function checkpoint is superuser-only"),
    "control_commit"                 -> Allow,
    "control_copy_from"              -> Allow, // insert into c, the reader's path authorized
    "control_copy_to"                -> Allow, // an authorized folder, outside the data folder
    "control_deallocate"             -> Allow,
    "control_detach"                 -> superuserOnly("detach"),
    "control_drop_secret"            -> superuserOnly("drop_secret"),
    "control_explain"                -> Allow,
    "control_explain_analyze"        -> Allow,
    "control_install"                -> superuserOnly("install"),
    "control_load"                   -> superuserOnly("load"),
    "control_load_url"               -> superuserOnly("load"),
    "control_pragma_credential_args" -> Refuse("function add_parquet_key is superuser-only"),
    "control_prepare"                -> Allow,
    "control_vacuum"                 -> superuserOnly("vacuum"),
    // DDL: RW never covers it, and macros, triggers and secure views are superuser-only
    "ddl_alter_table"         -> lacks("memory.main.t:alter"),
    "ddl_alter_view"          -> lacks("memory.main.pv:alter"),
    "ddl_create_fk"           -> lacks("memory.main.fk_child:create, memory.main.pk_parent:alter"),
    "ddl_create_index"        -> lacks("memory.main.t:alter, memory.main.t_id:create"),
    "ddl_create_macro"        -> Refuse("create macro memory.main.m2 is superuser-only"),
    "ddl_create_schema"       -> lacks("memory.s2:create"),
    "ddl_create_secure_view"  -> Refuse("create secure view memory.main.svx is superuser-only"),
    "ddl_create_sequence"     -> lacks("memory.main.seq2:create"),
    "ddl_create_table"        -> lacks("memory.main.n:create"),
    "ddl_create_temp_table"   -> Allow, // the session's temp catalog
    "ddl_create_trigger"      -> Refuse("create trigger memory.main.tr2 is superuser-only"),
    "ddl_create_type"         -> lacks("memory.main.mood:create"),
    "ddl_create_view"         -> lacks("memory.main.vw:create"),
    "ddl_ctas"                -> lacks("memory.main.n:create"),
    "ddl_drop_index"          -> lacks("memory.main.t:alter, memory.main.t_idx:drop"),
    "ddl_drop_macro"          -> Refuse("drop macro memory.main.shout is superuser-only"),
    "ddl_drop_missing"        -> lacks("memory.main.missing:drop"),
    "ddl_drop_schema"         -> lacks("memory.s1:drop"),
    "ddl_drop_secure_view"    -> Refuse("drop secure view memory.main.sv is superuser-only"),
    "ddl_drop_table"          -> lacks("memory.main.n0:drop"),
    "ddl_replace_secure_view" -> Refuse("create secure view memory.main.sv is superuser-only"),
    // writes
    "delete_returning"           -> Allow,
    "delete_where"               -> Allow,
    "insert_check_constraint"    -> Allow,
    "insert_default_nextval"     -> lacks("memory.main.seq1:update"), // the default advances seq1
    "insert_on_conflict_nothing" -> Allow,
    "insert_on_conflict_update"  -> Allow,
    "insert_select_returning"    -> lacks("memory.main.u:read"),
    "insert_values"              -> Allow,
    "merge_actions"              -> lacks("memory.main.u:read"),
    "update_where"               -> Allow,
    // functions: a reader's location is checked, no other function carries an argument
    "functions_credential_args"       -> Allow,
    "functions_credential_named_args" -> Allow,
    "functions_credential_scalar"     -> Allow,
    "functions_currval"               -> lacks("memory.main.seq1:read"),
    "functions_file_reader"           -> Allow,
    "functions_nextval"               -> lacks("memory.main.seq1:update"),
    "functions_range"                 -> Allow,
    // reads, macros and plain views: the reader's rights
    "macro_scalar"          -> Allow,
    "macro_table"           -> Allow,
    "query_function"        -> Allow,
    "read_aggregate"        -> Allow,
    "read_in_subquery"      -> lacks("memory.main.u:read"),
    "read_lambda"           -> Allow,
    "read_temp"             -> Allow,
    "read_union_dedupe"     -> Allow,
    "read_where"            -> Allow,
    "read_window"           -> Allow,
    "read_window_aggregate" -> Allow,
    "view_plain"            -> Allow,
    // settings
    "settings_explicit_session" -> Allow, // SET SESSION default_order
    "settings_global_default" -> Refuse("setting default_order at global scope is superuser-only"),
    "settings_global_threads" -> Refuse("setting threads is superuser-only"),
    "settings_reset"          -> Allow,
    "settings_session"        -> Allow, // search_path, session scope
    "settings_use"            -> Allow, // USE is the schema setting
    "settings_variable"       -> Allow, // SET VARIABLE: session data
    // a relative path is never authorized for a non-superuser
    "superuser_copy_to_relative" -> Refuse(
      "rel/out.csv is outside the tenant's authorized locations"
    ),
    "superuser_relative_path" -> Refuse("rel/x.csv is outside the tenant's authorized locations"),
    // secure views: what lies behind the boundary is covered by the RO grant on the outermost view
    "macro_table_over_secure"                       -> Allow,
    "read_correlated_secure"                        -> Allow,
    "view_plain_over_secure"                        -> Allow,
    "view_secure"                                   -> Allow,
    "view_secure_builtin_function"                  -> Allow,
    "view_secure_builtin_type"                      -> Allow,
    "view_secure_cross_catalog_named"               -> Allow, // the explicit m1 grant on the view
    "view_secure_cte"                               -> Allow,
    "view_secure_cte_name"                          -> Allow,
    "view_secure_cte_recursive"                     -> Allow,
    "view_secure_cte_recursive_anchor_qualified"    -> Allow,
    "view_secure_cte_recursive_using_key_qualified" -> Allow,
    "view_secure_cte_self_qualified"                -> Allow,
    "view_secure_dynamic_pivot"                     -> Allow,
    "view_secure_function"                          -> lacks(
      "memory.main.sv_fn:read, memory.main.u:read"
    ), // no grant on sv_fn
    "view_secure_information_schema" -> Allow, // listing functions through the view
    "view_secure_macro_body_ok"      -> Allow,
    "view_secure_macro_qualified"    -> Allow,
    "view_secure_manager_schema"     -> Allow,
    "view_secure_nested"             -> Allow, // judged on the outermost view, sv_outer
    "view_secure_qualified_tables"   -> Allow,
    "view_secure_reader"             -> Allow, // owner rights: no location check
    "view_secure_reader_relative"    -> Allow, // owner rights: no location check
    // a secure view is only ever read through: its nextval advances seq1
    "view_secure_sequence_qualified" -> Refuse(
      "memory.main.seq1 is reached through secure view memory.rpt.sv_seq by a update"
    ),
    "view_secure_table_macro_qualified"  -> Allow,
    "view_secure_temp_index"             -> Allow,
    "view_secure_temp_type_pivot_prefix" -> Allow,
    "view_secure_user_macro_qualified"   -> Allow
  )

  /** A grant on `schema`.`table` of the pool's own catalog (the `*` catalog). */
  private def perm(table: String, verb: String, schema: String = "main", catalog: String = "*") =
    RolePermission(s"p-$catalog-$schema-$table", "r-1", catalog, schema, table, verb)

  private val SecureViews = List(
    "sv",
    "sv_bi",
    "sv_csv",
    "sv_cte",
    "sv_ctes",
    "sv_int",
    "sv_is",
    "sv_mok",
    "sv_ord",
    "sv_outer",
    "sv_ra",
    "sv_rec",
    "sv_rel",
    "sv_shout",
    "sv_tm"
  )

  private val AlicePerms: List[RolePermission] =
    List("t", "c", "chk", "kv", "dflt").map(perm(_, "RW")) ++
      SecureViews.map(perm(_, "RO")) ++
      List(
        perm("sv_up", "RO", schema = "rpt"),
        perm("sv_seq", "RO", schema = "rpt"),
        perm("sv_pay", "RO", schema = "reporting"),
        // m1 is not the pool's attached catalog: only a grant naming it covers it.
        perm("sv_m1q", "RO", catalog = "m1")
      )

  private def alice(pool: PoolKey = Pool): DecidePrincipal =
    val effective = EffectiveSet(
      RbacUser("u-alice", Some("acme"), "alice", "user"),
      List(RbacRole("r-1", "acme", "analyst")),
      Nil,
      AlicePerms,
      Nil
    )
    DecidePrincipal(
      NodePrincipal("u-alice", "alice", Some("acme"), superuser = false, system = false),
      Some(DecideContext(effective, pool, None))
    )

  private val root: DecidePrincipal =
    DecidePrincipal(NodePrincipal("u-root", "root", None, superuser = true, system = false), None)

  /** The deny set: a DuckLake data folder and a duckdb-file database `db/out` (its name prefix
    * entry), both inside alice's folder, as the supervisor derives them.
    */
  private val DataPaths: List[String] =
    DataFolders.of(s"$Folder/lake/", sameHost = false, identity) ++
      DataFolders.of(s"$Folder/db/out${DataFolders.NamePrefixMarker}", sameHost = false, identity)

  private val opaCalls = new AtomicInteger(0)

  private val decider: AuthzDecider =
    val opaCfg = OpaConfig("qod", "", 500, 5)
    new AuthzDecider(
      tenantOf = Map("acme" -> Tenant("acme")).get,
      tenantCatalogs = Map("acme" -> Set("acme_mem", "acme_lake")).withDefaultValue(Set.empty),
      poolCatalog = Map(
        Pool     -> AuthzDecider.attachedCatalog(TenantDbKind.InMemory.wireValue, Map.empty),
        LakePool -> AuthzDecider
          .attachedCatalog(TenantDbKind.DuckLake.wireValue, Map("dbName" -> "acme_lake"))
      ).get(_).flatten,
      parentPoolsOf = _ => Nil,
      locations = new LocationRulesSource {
        def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
          Right(
            LocationRules(
              s3Scopes = Nil,
              tenantFolders = List(s"$Folder/"),
              dataFolders = DataPaths,
              filesRoot = Some(Root)
            )
          )
      },
      // QoD grants: OPA is never asked.
      opa = new OpaAuthorizer(
        opaCfg,
        new OpaClient(
          500,
          (_, _, _) => {
            opaCalls.incrementAndGet()
            Left("OPA must not be asked for a QoD-grants tenant")
          }
        ),
        new OpaDecisionCache(0),
        OpaInstruments.noop
      ),
      opaCfg = opaCfg
    )

  private def decide(json: String, p: DecidePrincipal, dir: String = Folder): Decision =
    Description.decode(json.replace("@DIR@", dir).getBytes(UTF_8)) match
      case Right(d)    => decider.decidePlan(p, d)
      case Left(error) => fail(s"does not decode: $error")

  "the golden corpus" should "be the one qod-node's describer_test pins" in {
    assume(Files.isDirectory(Dir), s"no $Dir")
    goldens.size should be >= 100
  }

  it should "be canonical JSON by Scala's rules too, byte for byte" in
    goldens.foreach { (name, text) =>
      withClue(name) {
        val json = parse(text).fold(e => fail(s"$name is not JSON: ${e.message}"), identity)
        CanonicalJson.render(json) shouldBe text
      }
    }

  it should "have a decision for every golden, and no decision without one" in {
    withClue("goldens without a decision:")(goldens.keySet -- expected.keySet shouldBe empty)
    withClue("decisions without a golden:")(expected.keySet -- goldens.keySet shouldBe empty)
  }

  "every golden" should "decide for alice as the table says" in {
    val mismatches = goldens.toList.sortBy(_._1).flatMap { (name, text) =>
      (decide(text, alice()), expected.get(name)) match
        case (Decision.Allow, Some(Allow))                                        => None
        case (Decision.Refuse(reason), Some(Refuse(why))) if reason.contains(why) => None
        case (_, None)                     => None // the key-set case names it
        case (decision, Some(expectation)) => Some(s"$name: expected $expectation, got $decision")
    }
    mismatches shouldBe empty
    opaCalls.get shouldBe 0
  }

  it should "be allowed for a superuser and the system principal" in
    goldens.foreach { (name, text) =>
      withClue(name) {
        decide(text, root) shouldBe Decision.Allow
        decide(text, DecidePrincipal(NodePrincipal.System, None)) shouldBe Decision.Allow
      }
    }

  "a wildcard catalog grant" should "cover memory only on a pool that attaches it" in {
    decide(goldens("read_where"), alice(LakePool)) shouldBe
      Decision.Refuse("user:alice lacks grants on memory.main.t:read")
  }

  "a COPY TO" should "be refused into a data folder, even inside an authorized folder" in {
    decide(goldens("control_copy_to"), alice(), s"$Folder/lake") shouldBe
      Decision.Refuse(s"$Folder/lake/out.parquet is a database data folder")
  }

  it should "be refused onto a name a duckdb-file database's file begins" in {
    decide(goldens("control_copy_to"), alice(), s"$Folder/db") shouldBe
      Decision.Refuse(s"$Folder/db/out.parquet is a database data folder")
  }

  it should "be refused into another tenant's folder" in {
    decide(goldens("control_copy_to"), alice(), "/srv/qod-files/globex/work") shouldBe
      Decision.Refuse(
        "/srv/qod-files/globex/work/out.parquet is outside the tenant's authorized locations"
      )
  }

  "a file reader" should "be refused outside the tenant's folders" in {
    decide(goldens("functions_file_reader"), alice(), "/srv/qod-files/globex/work") shouldBe
      Decision.Refuse(
        "/srv/qod-files/globex/work/in.csv is outside the tenant's authorized locations"
      )
    decide(goldens("control_copy_from"), alice(), "/etc") shouldBe
      Decision.Refuse("/etc/in.csv is outside the tenant's authorized locations")
  }

  it should "not be location-checked behind a secure view, which runs with owner rights" in {
    decide(goldens("view_secure_reader"), alice(), "/etc") shouldBe Decision.Allow
  }
