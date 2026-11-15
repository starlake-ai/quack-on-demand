package ai.starlake.quack.search

import ai.starlake.quack.edge.authz.{
  AuthzDecider,
  DecideContext,
  DecidePrincipal,
  DecidePurpose,
  Decision,
  Description,
  NodePrincipal
}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import scala.io.Source

/** Search Phase 0 part 2, Task 1 (spec 7.1 step 2): the real decider's answer, for a tenant user
  * holding the built-in `qod_all_tables` role, to the plan descriptions qod-node produced in
  * `search_source_probe_test` (copied from the probe's scratch output into
  * `src/test/resources/search/`): the nested statements of M1 and M2 (`nested-descriptions.jsonl`),
  * each read as its own top-level statement (`direct-descriptions.jsonl`) and the outer statements
  * (`outer-descriptions.jsonl`). Asserts only that every line decodes; the decisions are printed
  * for the evidence document.
  */
class NestedMetadataDecisionSpec extends AnyFlatSpec with Matchers:

  // The probe's lake is attached as `lake` on a node of acme's pool.
  private val Pool = PoolKey("acme", "acme_lake", "bi")

  private val AllTables = EffectiveSet(
    RbacUser("u-1", Some("acme"), "u-1", "user"),
    List(RbacRole("r-all", "acme", "qod_all_tables", builtin = true)),
    Nil,
    List(RolePermission("p-all", "r-all", "*", "*", "*", "ALL")),
    Nil
  )

  private val Principal = DecidePrincipal(
    NodePrincipal("u-1", "u-1", Some("acme"), superuser = false, system = false),
    Some(DecideContext(AllTables, Pool, None, DecidePurpose.Live))
  )

  private val OpaCfg = OpaConfig("qod", "", 500, 5)

  // A tenant user with no S3 credentials and no authorized folders: what a fresh tenant holds.
  private val decider = new AuthzDecider(
    tenantOf = Map("acme" -> Tenant("acme")).get,
    tenantCatalogs = Map("acme" -> Set("lake")).withDefaultValue(Set.empty[String]),
    poolCatalog = Map(Pool -> "lake").get,
    parentPoolsOf = _ => Nil,
    locations = new LocationRulesSource {
      def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
        Right(LocationRules(Nil, Nil, Nil, None))
    },
    opa = new OpaAuthorizer(
      OpaCfg,
      new OpaClient(500, (_, _, _) => Left("no OPA in this spec")),
      new OpaDecisionCache(0),
      OpaInstruments.noop
    ),
    opaCfg = OpaCfg
  )

  private def lines(resource: String): List[String] =
    Option(getClass.getResourceAsStream(resource)) match
      case None         => Nil
      case Some(stream) =>
        val source = Source.fromInputStream(stream, StandardCharsets.UTF_8.name)
        try source.getLines().filter(_.trim.nonEmpty).toList
        finally source.close()

  private def render(d: Decision): String = d match
    case Decision.Allow               => "decision=allow"
    case Decision.Refuse(reason)      => s"decision=refuse reason=$reason"
    case Decision.Unavailable(reason) => s"decision=unavailable reason=$reason"

  for name <- List("nested", "direct", "outer") do
    s"the $name descriptions of the source probe" should "decode, and the real decider answers each" in {
      val descriptions = lines(s"/search/$name-descriptions.jsonl")
      val labels       = lines(s"/search/$name-descriptions.index")
      println(s"== $name descriptions: ${descriptions.size}")
      val decoded = descriptions.zipWithIndex.map { case (line, i) =>
        val label = labels.lift(i).getOrElse("?").replace('\t', ' ')
        Description.decode(line.getBytes(StandardCharsets.UTF_8)) match
          case Left(why) =>
            println(s"$name[$i] $label: undecodable: $why")
            false
          case Right(d) =>
            println(s"$name[$i] $label: ${render(decider.decidePlan(Principal, d))}")
            true
      }
      all(decoded) shouldBe true
    }
