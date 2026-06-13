package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ColumnPolicyRewriterSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val superuser  = RbacUser(id = "u-super", tenant = None,         username = "root",  role = "admin")
  private val tenantUser = RbacUser(id = "u-1",     tenant = Some("acme"), username = "alice", role = "user")

  private def eff(user: RbacUser, policies: List[RoleColumnPolicy] = Nil): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  private val emptyCat: ColumnCatalog = new ColumnCatalog.MapCatalog(Map.empty)
  private def rw: ColumnPolicyRewriter = new ColumnPolicyRewriter(emptyCat)
  private val ctx = SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

  "rewrite" should "passthrough for superusers" in {
    rw.rewrite("SELECT c_email FROM customer", StatementKind.Select, eff(superuser), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough when the user has no column policies" in {
    rw.rewrite("SELECT c_email FROM customer", StatementKind.Select, eff(tenantUser, Nil), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough for non-Read statement kinds" in {
    val policies = List(RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer",
                                          "c_email", "mask", Some("'***'")))
    val effSet = eff(tenantUser, policies)
    rw.rewrite("INSERT INTO audit VALUES (1)", StatementKind.Dml,   effSet, ctx).unsafeRunSync() shouldBe Passthrough
    rw.rewrite("CREATE TABLE x(y INT)",        StatementKind.Ddl,   effSet, ctx).unsafeRunSync() shouldBe Passthrough
    rw.rewrite("BEGIN",                        StatementKind.Begin, effSet, ctx).unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough when the SQL fails to parse" in {
    val policies = List(RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer",
                                          "c_email", "mask", Some("'***'")))
    rw.rewrite("SELEC' WRONG", StatementKind.Select, eff(tenantUser, policies), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }