package ai.starlake.quack.edge

import ai.starlake.quack.edge.cls.SchemaContext
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[FlightSqlRouter.copyListingRefusal]]: the edge metadata filter cannot read a COPY, so for a
  * principal it narrows, a COPY could write a listing the filter would have narrowed to a file.
  */
class CopyListingRefusalSpec extends AnyFlatSpec with Matchers:

  private val filter    = new MetadataFilterRewriter(enabled = true)
  private val schemaCtx = SchemaContext(Some("lake"), Some("main"), Set("lake"))

  private def perm(c: String, t: String, v: String) =
    RolePermission(s"rp-$t-$v", "role-1", c, "main", t, v, None)

  private val alice = EffectiveSet(
    RbacUser("u-1", Some("t-1"), "alice", "user"),
    Nil,
    Nil,
    List(perm("lake", "orders", "RO"), perm("lake", "events", "RW")),
    Nil
  )
  private val wildcard =
    alice.copy(permissions = List(RolePermission("rp-all", "role-1", "*", "*", "*", "ALL", None)))
  private val root = EffectiveSet(RbacUser("u-0", None, "root", "admin"), Nil, Nil, Nil, Nil)

  private def refusal(sql: String, eff: EffectiveSet = alice): Option[String] =
    FlightSqlRouter.copyListingRefusal(
      sql,
      filter.narrows(eff, schemaCtx),
      s => filter.rewrite(s, eff, schemaCtx)
    )

  "a COPY of filtered metadata" should "be refused, whatever form names the listing" in {
    List(
      "COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "COPY (SHOW TABLES FROM tpch1) TO 's3://acme-etl/out/t.csv'"
    ).foreach(sql => withClue(sql)(refusal(sql) shouldBe Some(FlightSqlRouter.UnreadCopyRefusal)))
    List(
      "COPY (SELECT table_name, column_name FROM information_schema./**/columns) TO " +
        "'s3://acme-etl/out/c.csv'",
      "COPY (SELECT table_name FROM information_schema.tables) TO 's3://acme-etl/out/t.csv'",
      "COPY information_schema./**/tables TO 's3://acme-etl/out/t.csv'",
      "COPY lake.information_schema.columns TO 's3://acme-etl/out/c.csv'"
    ).foreach(sql => withClue(sql)(refusal(sql) shouldBe Some(FlightSqlRouter.ListingCopyRefusal)))
  }

  it should "be refused in a batch or behind a wrapper, whatever it copies" in
    List(
      "SELECT 1; COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
      "EXPLAIN ANALYZE COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
      "PREPARE p AS COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'"
    ).foreach(sql => withClue(sql)(refusal(sql) shouldBe Some(FlightSqlRouter.UnreadCopyRefusal)))

  it should "leave an ordinary COPY alone" in
    List(
      "COPY (SELECT * FROM main.orders) TO 's3://acme-etl/out/o.csv'",
      "COPY lake.main.orders TO 's3://acme-etl/out/o.csv'",
      "COPY lake.main.events FROM 's3://acme-etl/in/e.csv'",
      "SELECT * FROM read_parquet('s3://acme-etl/in/x.parquet')"
    ).foreach(sql => withClue(sql)(refusal(sql) shouldBe None))

  it should "be left to the node for a principal the filter does not narrow" in
    List(
      "COPY (SHOW TABLES) TO 's3://acme-etl/out/t.csv'",
      "SELECT 1; COPY (SELECT table_name FROM information_schema.tables) TO 's3://x/t.csv'"
    ).foreach { sql =>
      withClue(sql) {
        refusal(sql, wildcard) shouldBe None
        refusal(sql, root) shouldBe None
      }
    }
