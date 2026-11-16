package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.edge.search.SearchFunctions
import ai.starlake.quack.model.{PoolKey, Tenant, TenantAcl}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{
  RbacRole,
  RbacUser,
  RoleColumnPolicy,
  RolePermission,
  RoleRowPolicy
}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Search spec 4.2 rules 5 and 8, 7.5: the statistics population and the index record the decider
  * gives each search call of an allowed plan, and their place in the verdict wire.
  */
class AuthzDeciderSearchSpec extends AnyFlatSpec with Matchers:

  private val Pool    = PoolKey("acme", "acme_lake", "bi")
  private val OpaPool = PoolKey("opaco", "opaco_lake", "bi")
  private val Root    = "/data/lake/"

  private def perm(id: String, cat: String, sch: String, tab: String, verb: String) =
    RolePermission(id, "r-1", cat, sch, tab, verb)

  private val PagesRead = List(perm("p-1", "acme_lake", "main", "*", "RO"))

  private def eff(
      perms: List[RolePermission] = PagesRead,
      tenant: String = "acme",
      columnPolicies: List[RoleColumnPolicy] = Nil,
      rowPolicies: List[RoleRowPolicy] = Nil
  ) = EffectiveSet(
    RbacUser("u-1", Some(tenant), "alice", "user"),
    List(RbacRole("r-1", tenant, "analyst")),
    Nil,
    perms,
    Nil,
    columnPolicies = columnPolicies,
    rowPolicies = rowPolicies
  )

  private def principal(e: EffectiveSet, pool: PoolKey = Pool) = DecidePrincipal(
    NodePrincipal(e.user.id, e.user.username, e.user.tenant, superuser = false, system = false),
    Some(DecideContext(e, pool, None, DecidePurpose.Live))
  )

  private val plainUser = principal(eff())

  private def rlsUser(catalog: String, schema: String, table: String) =
    principal(
      eff(rowPolicies = List(RoleRowPolicy("rp-1", "r-1", catalog, schema, table, "f10 = 0")))
    )

  private def maskUser(catalog: String, schema: String, table: String, column: String) =
    principal(
      eff(columnPolicies =
        List(
          RoleColumnPolicy("cp-1", "r-1", catalog, schema, table, column, "mask", Some("'***'"))
        )
      )
    )

  private val otherOnlyUser =
    principal(eff(perms = List(perm("p-1", "acme_lake", "main", "other", "RO"))))

  private val opaUser = principal(
    eff(perms = List(perm("p-1", "opaco_lake", "main", "*", "RO")), tenant = "opaco"),
    OpaPool
  )

  private val superuser =
    DecidePrincipal(NodePrincipal("root", "root", None, superuser = true, system = false), None)
  private val system = DecidePrincipal(NodePrincipal.System, None)

  private final class Fixture(root: PoolKey => Option[String] = _ => Some(Root)):
    val opaCfg  = OpaConfig("qod", "", 500, 5)
    val decider = new AuthzDecider(
      tenantOf = Map(
        "acme"  -> Tenant("acme"),
        "opaco" -> Tenant("opaco", acl = TenantAcl(Some("opa"), Some("http://opa")))
      ).get,
      tenantCatalogs = Map("acme" -> Set("acme_lake"), "opaco" -> Set("opaco_lake"))
        .withDefaultValue(Set.empty[String]),
      poolCatalog = Map(Pool -> "acme_lake", OpaPool -> "opaco_lake").get,
      parentPoolsOf = _ => Nil,
      locations = new LocationRulesSource {
        def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
          Right(LocationRules.Empty)
      },
      opa = new OpaAuthorizer(
        opaCfg,
        new OpaClient(500, (_, _, _) => Right((200, """{"result":{"allow":true}}"""))),
        new OpaDecisionCache(0),
        OpaInstruments.noop
      ),
      opaCfg = opaCfg,
      functionClass = (_, _, name, _) => Option.when(name == "qod_search")(FunctionClass.Allowed),
      settingClass = _ => None,
      searchBundleRoot = root
    )

  private val decider = new Fixture().decider

  private def tableParts(table: String): (String, String, String) =
    table.split('.') match
      case Array(c, s, n) => (c, s, n)
      case _              => fail(s"not catalog.schema.table: $table")

  private def read(table: String) =
    val (c, s, n) = tableParts(table)
    DescribedObject("table", "read", c, "ducklake", s, n, List("body"), None)

  private def searchOn(table: String, calls: Int = 1): Description =
    Description(
      "PROJECTION",
      List(read(table)),
      List(DescribedFunction("system", "main", "qod_search", "table", Nil, None)),
      Nil,
      Nil,
      (0 until calls).toList.map(i => DescribedSearch(i, table, List("body")))
    )

  private def poolOf(p: DecidePrincipal): PoolKey = p.context.fold(Pool)(_.poolKey)

  private def decide(
      p: DecidePrincipal,
      d: Description,
      on: AuthzDecider = decider
  ): (Decision, List[SearchPopulation]) =
    on.decidePlan(p, d) match
      case Decision.Allow =>
        on.searchPopulations(p, d, poolOf(p)) match
          case Left(refusal) => (refusal, Nil)
          case Right(list)   => (Decision.Allow, list)
      case other => (other, Nil)

  private def populations(r: (Decision, List[SearchPopulation])): List[(Int, String)] =
    r._2.map(s => s.call -> s.population)

  private def golden(file: String, key: String): String =
    Files
      .readAllLines(Paths.get(s"native/qod-node/tests/golden/$file"))
      .asScala
      .collectFirst { case l if l.startsWith(s"$key=") => l.drop(key.length + 1) }
      .getOrElse(fail(s"$file has no $key="))

  private val testIndexId = SearchFunctions.prototypeIndexId("acme_lake.main.pages_1m", "body")

  private def verdictWith(search: List[SearchPopulation]): Verdict = Verdict(
    kind = "plan",
    decision = "allow",
    reason = "",
    retryable = false,
    principal = "u-1",
    policyVersion = 42L,
    fingerprint = golden("verdict-v1-search.txt", "fingerprint"),
    expiresAt = 1761200060000L,
    ctx = golden("verdict-v1-search.txt", "ctx"),
    search = search
  )

  private val goldenSearchVerdict = golden("verdict-v1-search.txt", "json")

  "a search description" should "get the table population for a principal with no row policy on the table" in {
    populations(decide(plainUser, searchOn("acme_lake.main.pages_1m"))) shouldBe List(0 -> "table")
  }

  it should "get the allowed population for a principal holding a row policy covering the table" in {
    populations(
      decide(rlsUser("*", "main", "pages_1m"), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "allowed")
  }

  it should "get the table population when the policy covers another table only" in {
    populations(
      decide(rlsUser("*", "main", "other"), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "table")
  }

  it should "get the allowed population for a policy written on the pool's defaultDatabase (review I1)" in {
    // The pool's defaultDatabase is lake2 while its catalog alias is acme_lake: the RLS rewriter
    // qualifies a bare pages_1m as lake2.main.pages_1m, so the lake2 policy filters the input, and
    // the node resolves the same bare name to acme_lake.main.pages_1m.
    populations(
      decide(rlsUser("lake2", "main", "pages_1m"), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "allowed")
  }

  it should "get the allowed population for a policy on another schema's same-named table" in {
    // A deliberate over-match: the population reads a row policy's table part alone, since the
    // rewriter's qualification and the node's may differ; `allowed` over an unfiltered input
    // discloses nothing.
    populations(
      decide(rlsUser("acme_lake", "staging", "PAGES_1M"), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "allowed")
  }

  it should "get the table population for a principal holding no row policy at all" in {
    val none = principal(eff(rowPolicies = Nil))
    populations(decide(none, searchOn("acme_lake.main.pages_1m"))) shouldBe List(0 -> "table")
  }

  it should "not count a row policy whose table part is blank" in {
    populations(
      decide(rlsUser("*", "*", ""), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "table")
  }

  it should "give each call its own population" in {
    val two = searchOn("acme_lake.main.pages_1m").copy(searches =
      List(
        DescribedSearch(0, "acme_lake.main.pages_1m", List("body")),
        DescribedSearch(1, "acme_lake.main.other", List("body"))
      )
    )
    populations(decide(rlsUser("*", "main", "pages_1m"), two)) shouldBe
      List(0 -> "allowed", 1 -> "table")
  }

  it should "refuse a search column carrying a column policy (rule 5)" in {
    decide(maskUser("*", "main", "pages_1m", "body"), searchOn("acme_lake.main.pages_1m"))._1 match
      case Decision.Refuse(why) => why should include("search column body carries a column policy")
      case other                => fail(s"expected a refusal, got $other")
  }

  it should "refuse a search column a wildcard column policy covers (rule 5)" in {
    decide(
      maskUser("acme_lake", "main", "pages_1m", "*"),
      searchOn("acme_lake.main.pages_1m")
    )._1 shouldBe
      Decision.Refuse("search column body carries a column policy")
  }

  it should "not refuse for a column policy on another column or table" in {
    populations(
      decide(maskUser("*", "main", "pages_1m", "title"), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "table")
    populations(
      decide(maskUser("*", "main", "other", "body"), searchOn("acme_lake.main.pages_1m"))
    ) shouldBe List(0 -> "table")
  }

  it should "refuse a search on a table without a read grant (rule 5)" in {
    decide(otherOnlyUser, searchOn("acme_lake.main.pages_1m"))._1 shouldBe a[Decision.Refuse]
  }

  it should "give an OPA tenant's principal the table population" in {
    // The tenant's acl_mode is opa, and the OPA stub answers allow.
    populations(decide(opaUser, searchOn("opaco_lake.main.pages_1m"))) shouldBe List(0 -> "table")
  }

  it should "give an OPA tenant's principal holding a row policy the allowed population" in {
    val rls = principal(
      eff(
        perms = List(perm("p-1", "opaco_lake", "main", "*", "RO")),
        tenant = "opaco",
        rowPolicies = List(RoleRowPolicy("rp-1", "r-1", "*", "*", "pages_1m", "f10 = 0"))
      ),
      OpaPool
    )
    populations(decide(rls, searchOn("opaco_lake.main.pages_1m"))) shouldBe List(0 -> "allowed")
  }

  it should "carry the index record: the prototype index id and the pool's bundle root" in {
    decide(plainUser, searchOn("acme_lake.main.pages_1m"))._2 shouldBe
      List(SearchPopulation(0, "table", testIndexId, Root))
  }

  it should "give a superuser and the system principal the table population, judging nothing" in {
    // No grant, a mask on the column and an unclassified function: nothing of it is judged.
    val d = searchOn("acme_lake.main.pages_1m", calls = 2).copy(functions =
      List(DescribedFunction("system", "main", "unclassified", "table", Nil, None))
    )
    for p <- List(superuser, system) do
      withClue(p.principal.name) {
        decide(p, d) shouldBe (
          Decision.Allow,
          List(
            SearchPopulation(0, "table", testIndexId, Root),
            SearchPopulation(1, "table", testIndexId, Root)
          )
        )
      }
  }

  it should "refuse a call with no index record (no bundle root for the pool)" in {
    val none = new Fixture(root = _ => None).decider
    decide(plainUser, searchOn("acme_lake.main.pages_1m"), none)._1 shouldBe Decision.Refuse(
      "search_index_unavailable: no index record for acme_lake.main.pages_1m (body)"
    )
    decide(superuser, searchOn("acme_lake.main.pages_1m"), none)._1 shouldBe a[Decision.Refuse]
  }

  it should "be unavailable when the bundle root cannot be read" in {
    val failing = new Fixture(root = _ => throw new IllegalStateException("store down")).decider
    decide(plainUser, searchOn("acme_lake.main.pages_1m"), failing)._1 shouldBe
      Decision.Unavailable(AuthzDecider.SearchRecordUnavailable)
  }

  it should "be unavailable for a user handle with no context" in {
    val noContext = plainUser.copy(context = None)
    decider.searchPopulations(noContext, searchOn("acme_lake.main.pages_1m"), Pool) shouldBe
      Left(Decision.Unavailable(AuthzDecider.ContextExpired))
  }

  it should "refuse a pool other than the context's" in {
    decider.searchPopulations(
      plainUser,
      searchOn("acme_lake.main.pages_1m"),
      PoolKey("acme", "acme_lake", "other")
    ) shouldBe a[Left[?, ?]]
  }

  it should "refuse calls numbered other than 0 to n-1, and a call on several columns" in {
    val gap = searchOn("acme_lake.main.pages_1m").copy(searches =
      List(DescribedSearch(1, "acme_lake.main.pages_1m", List("body")))
    )
    decide(plainUser, gap)._1 shouldBe a[Decision.Refuse]
    val dup = searchOn("acme_lake.main.pages_1m", calls = 2)
      .copy(searches = List.fill(2)(DescribedSearch(0, "acme_lake.main.pages_1m", List("body"))))
    decide(plainUser, dup)._1 shouldBe a[Decision.Refuse]
    val wide = searchOn("acme_lake.main.pages_1m").copy(searches =
      List(DescribedSearch(0, "acme_lake.main.pages_1m", List("body", "title")))
    )
    decide(plainUser, wide)._1 shouldBe a[Decision.Refuse]
  }

  it should "answer no populations for a plan holding no search call" in {
    decider.searchPopulations(
      plainUser,
      searchOn("acme_lake.main.pages_1m", calls = 0),
      Pool
    ) shouldBe
      Right(Nil)
  }

  "a description" should "decode searches when present and default to none" in {
    val with_ = golden("verdict-v1-search.txt", "description")
    Description.decode(with_.getBytes(UTF_8)).map(_.searches) shouldBe
      Right(List(DescribedSearch(0, "acme_lake.main.pages_1m", List("body"))))
    val without = golden("verdict-v1.txt", "description")
    Description.decode(without.getBytes(UTF_8)).map(_.searches) shouldBe Right(Nil)
  }

  it should "decode qod-node's own search description golden" in {
    val bytes = Files.readAllBytes(
      Paths.get("native/qod-node/tests/golden/descriptions/search-a1.json")
    )
    Description.decode(bytes).map(_.searches) shouldBe
      Right(List(DescribedSearch(0, "lake.main.pages", List("body"))))
  }

  "the verdict wire" should "carry search populations only when the description has searches" in {
    Verdicts.json(verdictWith(Nil)).noSpaces should not include "\"search\""
    CanonicalJson.render(
      Verdicts.json(verdictWith(List(SearchPopulation(0, "allowed", testIndexId, "/data/lake/"))))
    ) shouldBe goldenSearchVerdict
  }

  it should "sign exactly the golden search verdict qod-node verifies" in {
    val key = NodeHandles.nodeKey("qod-golden-node-key-secret-0123456789", "quack-acme-db-bi-1")
    NodeHandles.hex(key) shouldBe golden("verdict-v1-search.txt", "key")
    Verdicts.sign(
      verdictWith(List(SearchPopulation(0, "allowed", testIndexId, "/data/lake/"))),
      key
    ) shouldBe golden("verdict-v1-search.txt", "allow")
  }

  it should "sort the entries by call" in {
    val v = verdictWith(
      List(SearchPopulation(1, "table", "b", "/r/"), SearchPopulation(0, "allowed", "a", "/r/"))
    )
    parse(CanonicalJson.render(Verdicts.json(v))).toOption.get.hcursor
      .downField("search")
      .values
      .get
      .map(_.hcursor.get[Int]("call").toOption.get)
      .toList shouldBe List(0, 1)
  }

  it should "leave the no-search verdict bytes as verdict-v1.txt pins them" in {
    val key = NodeHandles.nodeKey("qod-golden-node-key-secret-0123456789", "quack-acme-db-bi-1")
    val v   = verdictWith(Nil).copy(
      fingerprint = golden("verdict-v1.txt", "fingerprint"),
      ctx = golden("verdict-v1.txt", "ctx")
    )
    Verdicts.sign(v, key) shouldBe golden("verdict-v1.txt", "allow")
  }

  "the golden search description" should "fingerprint as the golden says" in {
    MessageDigest
      .getInstance("SHA-256")
      .digest(golden("verdict-v1-search.txt", "description").getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString shouldBe golden("verdict-v1-search.txt", "fingerprint")
  }

  "the prototype index id" should "match the vectors qod-node is pinned to" in {
    val vectors = Files
      .readAllLines(Paths.get("native/qod-node/tests/golden/search-index-id-v1.txt"))
      .asScala
      .filterNot(l => l.startsWith("#") || l.isBlank)
      .map(_.split('\t').toList)
      .toList
    vectors should not be empty
    vectors.foreach {
      case List(table, column, id) =>
        withClue(s"$table $column")(SearchFunctions.prototypeIndexId(table, column) shouldBe id)
      case other => fail(s"malformed vector: $other")
    }
  }
