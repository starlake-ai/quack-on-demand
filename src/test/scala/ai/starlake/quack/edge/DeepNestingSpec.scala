package ai.starlake.quack.edge

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{SqlParser, StatementResult}
import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter, SchemaContext}
import ai.starlake.quack.edge.meta.{MetadataFilterOutcome, MetadataFilterRewriter}
import ai.starlake.quack.edge.policy.{GuardOutcome, ProtectedWriteGuard, RewriteScope}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import ai.starlake.quack.route.RoutingRefs
import cats.effect.unsafe.implicits.global
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Audit F13: a statement nested deeply enough to overflow a recursive walk must be answered
  * fail-closed by every analysis step, never escape as a `StackOverflowError`. That error is fatal
  * to cats-effect: under `IOApp` one statement halted the whole manager.
  *
  * Each step runs on a thread with a 1 MiB stack, the JVM default of a production worker thread, so
  * the depth that overflows does not depend on the test runner's own thread.
  */
class DeepNestingSpec extends AnyFunSuite with Matchers:

  /** `body` on a 1 MiB stack; a throwable escaping it is returned, not rethrown. */
  private def onWorkerStack[A](body: => A): Either[Throwable, A] =
    var out: Either[Throwable, A] = Left(new IllegalStateException("not run"))
    val t                         = new Thread(
      null,
      () =>
        out =
          try Right(body)
          catch case e: Throwable => Left(e), "deep-nesting",
      1L << 20
    )
    t.start()
    t.join()
    out

  private def value[A](r: Either[Throwable, A]): A = r match
    case Right(a) => a
    case Left(e)  => fail(s"escaped ${e.getClass.getName}", e)

  // A left-deep operator chain, nested scalar subqueries, and the chain behind PREPARE ... AS.
  private val chain       = "SELECT " + List.fill(20000)("1").mkString("+")
  private val subqueries  = "SELECT " + "(SELECT " * 500 + "1" + ")" * 500
  private val prepared    = "PREPARE p AS " + chain
  private val insertChain = "INSERT INTO tpch1.t SELECT " + List.fill(20000)("c").mkString("+") +
    " FROM tpch1.customer"
  private val shapes = List("chain" -> chain, "subqueries" -> subqueries, "prepare" -> prepared)

  private val user = RbacUser("u-1", Some("acme"), "alice", kind = "user")
  private val mask =
    RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
  private val rows = RoleRowPolicy("rp-1", "r-1", "*", "tpch1", "customer", "c_region = 'X'")
  private val eff  = EffectiveSet(user, Nil, Nil, Nil, Nil, List(mask), List(rows))
  private val ctx  =
    SchemaContext(
      defaultDatabase = Some("acme_tpch"),
      defaultSchema = Some("tpch1"),
      attachedCatalogs = Set.empty
    )
  private val cat = new ColumnCatalog.MapCatalog(
    Map(("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_region"))
  )
  private val acl = Config.forDuckDB("db", "main")

  shapes.foreach { (name, sql) =>
    test(s"SqlParser.extract answers a parse error on a too-deep $name, which the ACL denies") {
      val r = value(onWorkerStack(SqlParser.extract(sql, acl)))
      r.statements should not be empty
      all(r.statements) shouldBe a[StatementResult.ParseError]
    }

    test(s"RewriteScope compares a too-deep $name without overflowing") {
      value(onWorkerStack(RewriteScope.reachesBeyond(sql, sql + " ", acl, "db", false)))
    }

    test(s"RoutingRefs reads no table from a too-deep $name") {
      value(onWorkerStack(RoutingRefs.extract(sql, acl))) shouldBe RoutingRefs.empty
    }
  }

  test("SqlParser.extractSingle answers a parse error on a too-deep statement") {
    value(onWorkerStack(SqlParser.extractSingle(chain, acl))) shouldBe a[
      StatementResult.ParseError
    ]
  }

  test("ProtectedWriteGuard denies a too-deep write for a principal with policies") {
    value(
      onWorkerStack(
        new ProtectedWriteGuard(cat, clsEnabled = true, rlsEnabled = true)
          .check(insertChain, StatementKind.Dml, eff, ctx)
      )
    ) shouldBe a[GuardOutcome.Deny]
  }

  test("ColumnPolicyRewriter denies a too-deep read for a column-policy holder") {
    val outcome = value(
      onWorkerStack(
        new ColumnPolicyRewriter(cat).rewrite(chain, StatementKind.Select, eff, ctx).unsafeRunSync()
      )
    )
    outcome should not be ColumnPolicyRewriter.Passthrough
    outcome should not be a[ColumnPolicyRewriter.Rewritten]
  }

  test("MetadataFilterRewriter denies a too-deep read") {
    value(
      onWorkerStack(new MetadataFilterRewriter(enabled = true).rewrite(chain, eff, ctx))
    ) shouldBe a[MetadataFilterOutcome.Denied]
  }
