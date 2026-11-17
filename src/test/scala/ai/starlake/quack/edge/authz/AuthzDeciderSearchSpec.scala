package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
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

/** Search spec 4.2 rules 5 and 8, 7.5: the statistics population the decider gives each search call
  * of an allowed plan, and its place in the verdict wire. A verdict carries the population only:
  * index records come from their own route (spec ruling 18, OWNER RULING 2026-11-16 D4).
  */
class AuthzDeciderSearchSpec extends AnyFlatSpec with Matchers:

  private val Pool    = PoolKey("acme", "acme_lake", "bi")
  private val OpaPool = PoolKey("opaco", "opaco_lake", "bi")

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

  private final class Fixture:
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
      settingClass = _ => None
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

  it should "get the allowed population for a policy naming another catalog (review I1)" in {
    // The population reads the table part alone (spec 7.5, P9): since PR #228 the rewriter
    // qualifies a bare name with the session path the node resolves it in, so this over-match
    // costs only the statistics' scope.
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

  it should "match a policy on *.*.pages by table name, over-including other catalogs (7.5)" in {
    val star = principal(
      eff(rowPolicies = List(RoleRowPolicy("rp-1", "r-1", "*", "*", "pages", "f10 = 0")))
    )
    for table <- List("acme_lake.main.pages", "other.main.pages") do
      withClue(table) {
        decider.searchPopulations(star, searchOn(table), Pool) shouldBe
          Right(List(SearchPopulation(0, SearchPopulation.Allowed)))
        decider.searchPopulations(plainUser, searchOn(table), Pool) shouldBe
          Right(List(SearchPopulation(0, SearchPopulation.Table)))
      }
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
    val two = searchOn("acme_lake.main.pages_1m").copy(
      objects = List(read("acme_lake.main.pages_1m"), read("acme_lake.main.other")),
      searches = List(
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

  it should "answer unavailable for a search on a table named only by the call, with no object read of it" in {
    // The description reads `other` (granted) and its search names pages_1m (not granted) with no
    // object read of it: the search column must be an object read the decider judged (rule 5).
    val d = searchOn("acme_lake.main.pages_1m").copy(objects = List(read("acme_lake.main.other")))
    // A description the node never writes (it describes every search column so): a node bug, so
    // unavailable, never a refusal booked as the principal's denial (Task 5 review M3, Task 10).
    decide(otherOnlyUser, d)._1 match
      case Decision.Unavailable(why) =>
        why should include(
          "unreadable search description: search column body of acme_lake.main.pages_1m"
        )
      case other => fail(s"expected an unavailable decision, got $other")
  }

  it should "answer unavailable for a search column read only through a secure view" in {
    // A read through a secure view is judged with its owner's rights, never the principal's.
    val viaView = searchOn("acme_lake.main.pages_1m").copy(objects =
      List(read("acme_lake.main.pages_1m").copy(via = Some("acme_lake.main.sv")))
    )
    decider.searchPopulations(plainUser, viaView, Pool) match
      case Left(Decision.Unavailable(why)) =>
        why should startWith("unreadable search description: ")
      case other => fail(s"expected an unavailable decision, got $other")
  }

  it should "answer unavailable for a search column that is not among the object read's columns" in {
    val other = searchOn("acme_lake.main.pages_1m").copy(objects =
      List(read("acme_lake.main.pages_1m").copy(columns = List("title")))
    )
    decider.searchPopulations(plainUser, other, Pool) match
      case Left(Decision.Unavailable(why)) =>
        why should startWith("unreadable search description: ")
      case o => fail(s"expected an unavailable decision, got $o")
  }

  it should "answer unavailable for a search table that does not split into three non-empty parts (review m1)" in {
    // Java's split drops trailing empty parts: "pages." would read as acme_lake.main.pages.
    for table <- List(
        "acme_lake.main.pages_1m.",
        "acme_lake..pages_1m",
        ".main.pages_1m",
        "main.pages_1m"
      )
    do
      withClue(table) {
        val d = searchOn("acme_lake.main.pages_1m").copy(searches =
          List(DescribedSearch(0, table, List("body")))
        )
        decider.searchPopulations(plainUser, d, Pool) shouldBe
          Left(
            Decision.Unavailable(
              s"unreadable search description: search table '$table' is not catalog.schema.table"
            )
          )
      }
  }

  it should "refuse a search column a column policy covers by table name alone (review m2)" in {
    // Rule 5 fails closed: a policy written under another catalog or another schema still refuses
    // the column, as the population matches row policies on the table part.
    for (cat, sch) <- List(("lake2", "main"), ("acme_lake", "staging"), ("other", "other")) do
      withClue(s"$cat.$sch") {
        decide(
          maskUser(cat, sch, "PAGES_1M", "body"),
          searchOn("acme_lake.main.pages_1m")
        )._1 shouldBe
          Decision.Refuse("search column body carries a column policy")
      }
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

  it should "give a superuser and the system principal the table population, judging nothing" in {
    // No grant, a mask on the column and an unclassified function: nothing of it is judged.
    val d = searchOn("acme_lake.main.pages_1m", calls = 2).copy(functions =
      List(DescribedFunction("system", "main", "unclassified", "table", Nil, None))
    )
    for p <- List(superuser, system) do
      withClue(p.principal.name) {
        decide(p, d) shouldBe (
          Decision.Allow,
          List(SearchPopulation(0, "table"), SearchPopulation(1, "table"))
        )
      }
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

  it should "answer unavailable for calls numbered other than 0 to n-1, and for a call on no column" in {
    val gap = searchOn("acme_lake.main.pages_1m").copy(searches =
      List(DescribedSearch(1, "acme_lake.main.pages_1m", List("body")))
    )
    decide(plainUser, gap)._1 shouldBe
      Decision.Unavailable("unreadable search description: search calls are not numbered 0 to n-1")
    val dup = searchOn("acme_lake.main.pages_1m", calls = 2)
      .copy(searches = List.fill(2)(DescribedSearch(0, "acme_lake.main.pages_1m", List("body"))))
    decide(plainUser, dup)._1 shouldBe
      Decision.Unavailable("unreadable search description: search calls are not numbered 0 to n-1")
    val none = searchOn("acme_lake.main.pages_1m").copy(searches =
      List(DescribedSearch(0, "acme_lake.main.pages_1m", Nil))
    )
    decide(plainUser, none)._1 shouldBe Decision.Unavailable(
      "unreadable search description: a search call on acme_lake.main.pages_1m names no column"
    )
  }

  it should "judge every column of a call on several columns (rule 5)" in {
    val wide = searchOn("acme_lake.main.pages_1m").copy(
      objects = List(read("acme_lake.main.pages_1m").copy(columns = List("body", "title"))),
      searches = List(DescribedSearch(0, "acme_lake.main.pages_1m", List("body", "title")))
    )
    populations(decide(plainUser, wide)) shouldBe List(0 -> "table")
    decide(maskUser("*", "main", "pages_1m", "title"), wide)._1 shouldBe
      Decision.Refuse("search column title carries a column policy")
    // A column the plan does not read is not taken on the call's word.
    val unread = wide.copy(objects = List(read("acme_lake.main.pages_1m")))
    decide(plainUser, unread)._1 shouldBe a[Decision.Unavailable]
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
      Verdicts.json(verdictWith(List(SearchPopulation(0, "allowed"))))
    ) shouldBe goldenSearchVerdict
  }

  it should "carry call and population only in each entry, never an index record" in {
    val entries = parse(
      CanonicalJson.render(
        Verdicts.json(
          verdictWith(List(SearchPopulation(0, "allowed"), SearchPopulation(1, "table")))
        )
      )
    ).toOption.get.hcursor.downField("search").values.get.toList
    entries.map(_.asObject.get.keys.toSet) shouldBe List.fill(2)(Set("call", "population"))
    goldenSearchVerdict should not include "indexId"
    goldenSearchVerdict should not include "bundleRoot"
  }

  // Regenerates native/qod-node/tests/golden/verdict-v1-search.txt's json= and allow= lines from
  // the encoder (QOD_CAPTURE=1); every other line is kept. qod-node's verdict_test reads the file.
  it should "capture the golden search verdict" in {
    // A plain Boolean: ScalaTest's assume prints the values of its expression when it cancels.
    val capture: Boolean = sys.env.contains("QOD_CAPTURE")
    assume(capture, "set QOD_CAPTURE to capture the golden file")
    val key  = NodeHandles.nodeKey("qod-golden-node-key-secret-0123456789", "quack-acme-db-bi-1")
    val v    = verdictWith(List(SearchPopulation(0, "allowed")))
    val path = Paths.get("native/qod-node/tests/golden/verdict-v1-search.txt")
    val kept = Files.readAllLines(path).asScala.toList.map { l =>
      if l.startsWith("json=") then "json=" + CanonicalJson.render(Verdicts.json(v))
      else if l.startsWith("allow=") then "allow=" + Verdicts.sign(v, key)
      else l
    }
    Files.write(path, kept.mkString("", "\n", "\n").getBytes(UTF_8))
    info(s"captured ${kept.size} lines to $path")
  }

  it should "sign exactly the golden search verdict qod-node verifies" in {
    val key = NodeHandles.nodeKey("qod-golden-node-key-secret-0123456789", "quack-acme-db-bi-1")
    NodeHandles.hex(key) shouldBe golden("verdict-v1-search.txt", "key")
    Verdicts.sign(
      verdictWith(List(SearchPopulation(0, "allowed"))),
      key
    ) shouldBe golden("verdict-v1-search.txt", "allow")
  }

  it should "sort the entries by call" in {
    val v = verdictWith(
      List(SearchPopulation(1, "table"), SearchPopulation(0, "allowed"))
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
