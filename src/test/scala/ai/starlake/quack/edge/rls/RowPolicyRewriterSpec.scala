package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RowPolicyRewriterSpec extends AnyFlatSpec with Matchers:
  import RowPolicyRewriter._

  private val superuser  = RbacUser(id = "u-super", tenant = None, username = "root", role = "admin")
  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", role = "user")

  private def policy(
      predicate: String,
      catalog: String = "*",
      schema: String = "tpch1",
      table: String = "customer",
      id: String = "rp-1"
  ): RoleRowPolicy =
    RoleRowPolicy(id, "r-1", catalog, schema, table, predicate)

  private def eff(
      user: RbacUser,
      rowPolicies: List[RoleRowPolicy] = Nil,
      roles: List[RbacRole] = Nil,
      groups: List[RbacGroup] = Nil
  ): EffectiveSet =
    EffectiveSet(user, roles, groups, Nil, Nil, Nil, rowPolicies)

  private def rw: RowPolicyRewriter = new RowPolicyRewriter(enabled = true)
  private val ctx = SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

  private def rewritten(o: Outcome): String = o match
    case Rewritten(sql) => sql.toLowerCase
    case other          => fail(s"expected Rewritten, got $other")

  // ---------- short-circuits ----------

  "rewrite" should "passthrough when the feature is disabled" in {
    val disabled = new RowPolicyRewriter(enabled = false)
    disabled.rewrite(
      "SELECT * FROM customer",
      StatementKind.Select,
      eff(tenantUser, List(policy("c_region = 'eu'"))),
      ctx
    ) shouldBe Passthrough
  }

  it should "passthrough for superusers" in {
    rw.rewrite(
      "SELECT * FROM customer",
      StatementKind.Select,
      eff(superuser, List(policy("c_region = 'eu'"))),
      ctx
    ) shouldBe Passthrough
  }

  it should "passthrough when the user has no row policies" in {
    rw.rewrite("SELECT * FROM customer", StatementKind.Select, eff(tenantUser, Nil), ctx) shouldBe
      Passthrough
  }

  it should "passthrough for non-Select statement kinds" in {
    val effSet = eff(tenantUser, List(policy("c_region = 'eu'")))
    rw.rewrite("INSERT INTO audit VALUES (1)", StatementKind.Dml, effSet, ctx) shouldBe Passthrough
    rw.rewrite("CREATE TABLE x(y INT)", StatementKind.Ddl, effSet, ctx) shouldBe Passthrough
    rw.rewrite("BEGIN", StatementKind.Begin, effSet, ctx) shouldBe Passthrough
  }

  it should "emit PassthroughParseFailed when the SQL fails to parse" in {
    rw.rewrite(
      "SELEC' WRONG",
      StatementKind.Select,
      eff(tenantUser, List(policy("c_region = 'eu'"))),
      ctx
    ) shouldBe PassthroughParseFailed
  }

  it should "passthrough when no referenced table matches a policy" in {
    rw.rewrite(
      "SELECT * FROM orders",
      StatementKind.Select,
      eff(tenantUser, List(policy("c_region = 'eu'", table = "customer"))),
      ctx
    ) shouldBe Passthrough
  }

  // ---------- wrapping ----------

  it should "wrap a matched base table in a filtered subselect" in {
    val sql = rewritten(
      rw.rewrite(
        "SELECT c_id FROM customer",
        StatementKind.Select,
        eff(tenantUser, List(policy("c_region = 'eu'"))),
        ctx
      )
    )
    sql should include("select * from customer where")
    sql should include("c_region = 'eu'")
  }

  it should "preserve the caller's table alias" in {
    val sql = rewritten(
      rw.rewrite(
        "SELECT c.c_id FROM customer c",
        StatementKind.Select,
        eff(tenantUser, List(policy("c_region = 'eu'"))),
        ctx
      )
    )
    // outer reference c.c_id must still resolve -> wrapper carries alias `c`
    sql should include("c_id")
    sql.replaceAll("\\s+", " ") should (include(") as c") or include(") c"))
  }

  it should "substitute the ${user} identity token with a quoted literal" in {
    val sql = rewritten(
      rw.rewrite(
        "SELECT * FROM customer",
        StatementKind.Select,
        eff(tenantUser, List(policy("c_owner = ${user}"))),
        ctx
      )
    )
    sql should include("c_owner = 'alice'")
  }

  it should "expand a ${groups} list token for an IN predicate" in {
    val groups = List(RbacGroup("g-1", "acme", "sales"), RbacGroup("g-2", "acme", "eng"))
    val sql = rewritten(
      rw.rewrite(
        "SELECT * FROM customer",
        StatementKind.Select,
        eff(tenantUser, List(policy("c_dept IN (${groups})")), groups = groups),
        ctx
      )
    )
    sql should include("'sales'")
    sql should include("'eng'")
  }

  it should "collapse an empty ${groups} list to NULL (matches no rows)" in {
    val sql = rewritten(
      rw.rewrite(
        "SELECT * FROM customer",
        StatementKind.Select,
        eff(tenantUser, List(policy("c_dept IN (${groups})"))),
        ctx
      )
    )
    sql.replaceAll("\\s+", " ") should include("in (null)")
  }

  it should "OR-combine multiple policies on the same table" in {
    val policies = List(
      policy("c_region = 'eu'", id = "rp-1"),
      policy("c_tier = 'gold'", id = "rp-2")
    )
    val sql = rewritten(
      rw.rewrite("SELECT * FROM customer", StatementKind.Select, eff(tenantUser, policies), ctx)
    )
    sql should include("c_region = 'eu'")
    sql should include("c_tier = 'gold'")
    sql should include(" or ")
  }

  it should "match a wildcard-table policy against any table" in {
    val sql = rewritten(
      rw.rewrite(
        "SELECT * FROM orders",
        StatementKind.Select,
        eff(tenantUser, List(policy("tenant_id = ${tenantId}", table = "*"))),
        ctx
      )
    )
    sql should include("select * from orders where")
    sql should include("tenant_id = 'acme'")
  }

  it should "filter both sides of a join" in {
    val policies = List(
      policy("c_region = 'eu'", table = "customer", id = "rp-1"),
      policy("o_year = 2024", table = "orders", id = "rp-2")
    )
    val sql = rewritten(
      rw.rewrite(
        "SELECT * FROM customer c JOIN orders o ON c.c_id = o.c_id",
        StatementKind.Select,
        eff(tenantUser, policies),
        ctx
      )
    )
    sql should include("c_region = 'eu'")
    sql should include("o_year = 2024")
  }

  it should "SQL-escape a single quote in a substituted identity value" in {
    val odd = RbacUser(id = "u-2", tenant = Some("acme"), username = "o'brien", role = "user")
    val sql = rewritten(
      rw.rewrite(
        "SELECT * FROM customer",
        StatementKind.Select,
        eff(odd, List(policy("c_owner = ${user}"))),
        ctx
      )
    )
    sql should include("'o''brien'")
  }
