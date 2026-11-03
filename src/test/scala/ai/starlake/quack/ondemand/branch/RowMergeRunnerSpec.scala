package ai.starlake.quack.ondemand.branch

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** The empty-set checks: a semi-join on an empty table reads the whole table (design finding 14),
  * so the runner must ask the sizes first and leave those reads out.
  */
class RowMergeRunnerSpec extends AnyFlatSpec with Matchers:

  private val t = RowMergeTable(
    0,
    "s",
    "t",
    List("id", "note"),
    Nil,
    List(SnapshotRange(11L, 12L)),
    List(SnapshotRange(11L, 11L))
  )

  private final class FakeNode(overlap: Long, need: Long, failOn: Option[String] = None)
      extends MergeNode:
    val lists                 = mutable.ListBuffer.empty[List[String]]
    def batches: List[String] = lists.toList.map(_.mkString("\n"))
    def exec(statements: List[String]): IO[Either[String, Unit]] = IO {
      lists += statements
      if failOn.exists(f => statements.exists(_.contains(f))) then Left("boom") else Right(())
    }
    def rows(sql: String): IO[Either[String, List[List[Json]]]] = IO {
      Right(
        if sql.contains("_need") then List(List(Json.fromLong(overlap), Json.fromLong(need)))
        else if sql.contains("AS kind") then Nil
        else List(List.fill(12)(Json.fromLong(0L)))
      )
    }

  private def runner = new RowMergeRunner("lake", "qod_branch", 10L, None)

  "prepare" should "create the working schema and read the branch, with settings first" in {
    val node = new FakeNode(0L, 0L)
    runner.prepare(node, List(t)).unsafeRunSync() shouldBe Right(())
    node.lists.size shouldBe 1
    node.lists.head.head shouldBe "SET dynamic_or_filter_threshold = 10000"
    node.lists.head(1) shouldBe """CREATE SCHEMA IF NOT EXISTS "memory"."qod_merge""""
    node.batches.head should include("t0_post_b")
  }

  "decide" should "not read the fork or main's values when nothing overlaps" in {
    val node = new FakeNode(overlap = 0L, need = 0L)
    runner.decide(node, List(t)).unsafeRunSync().isRight shouldBe true
    val all = node.batches.mkString("\n")
    all should not include "AT (VERSION => 10) WHERE rowid IN"
    node.batches
      .filter(_.contains("t0_post_m"))
      .mkString should not include "ducklake_table_insertions"
  }

  it should "read main's values but not the fork when every original value is already held" in {
    val node = new FakeNode(overlap = 4L, need = 0L)
    runner.decide(node, List(t)).unsafeRunSync().isRight shouldBe true
    node.batches.filter(_.contains("t0_post_m")).mkString should include(
      "ducklake_table_insertions('lake'"
    )
    node.batches.mkString("\n") should not include "AT (VERSION => 10) WHERE rowid IN"
  }

  it should "fetch only the original values still needed" in {
    val node = new FakeNode(overlap = 4L, need = 2L)
    runner.decide(node, List(t)).unsafeRunSync().isRight shouldBe true
    node.batches.mkString("\n") should include("AT (VERSION => 10) WHERE rowid IN")
  }

  it should "repeat the setting in every list: each call is a new connection" in {
    val node = new FakeNode(overlap = 4L, need = 2L)
    runner.decide(node, List(t)).unsafeRunSync()
    node.lists should not be empty
    node.lists.foreach(_.head shouldBe "SET dynamic_or_filter_threshold = 10000")
  }

  "every list" should "hold one statement per entry, whatever a table's names and shape render" in {
    val tables = List(
      t,
      t.copy(index = 1, table = "o;r", columns = List("a;b", "c'd"), keyColumns = List("a;b")),
      t.copy(index = 2, branchRanges = Nil),
      t.copy(index = 3, mainRanges = Nil, keyColumns = List("id"))
    )
    (None :: ConflictPolicy.values.toList.map(Some(_))).foreach { policy =>
      val node   = new FakeNode(overlap = 4L, need = 2L)
      val runner = new RowMergeRunner("la;ke", "qod_branch", 10L, policy)
      runner.prepare(node, tables).unsafeRunSync() shouldBe Right(())
      runner.warmUp(node, tables).unsafeRunSync() shouldBe Right(())
      runner.decide(node, tables).unsafeRunSync().isRight shouldBe true
      node.lists should not be empty
      node.lists.flatten.foreach(s => withClue(s)(OneStatement.holds(s) shouldBe true))
    }
  }

  "the check of an entry" should "tell a second statement from a semicolon inside a name or literal" in {
    OneStatement.holds("SELECT 1") shouldBe true
    OneStatement.holds("SELECT 'a;b', 'it''s;', \"c;d\"") shouldBe true
    OneStatement.holds("SELECT 1; SELECT 2") shouldBe false
    OneStatement.holds("SELECT 1;") shouldBe false
    OneStatement.holds("SELECT 'a;'; SELECT 2") shouldBe false
  }

  it should "stop at the first failure and report it" in {
    val node = new FakeNode(4L, 2L, failOn = Some("t0_net_m"))
    runner.decide(node, List(t)).unsafeRunSync() shouldBe Left("boom")
    node.batches.size shouldBe 1
  }

  "warmUp" should "fetch original values, and write no decision" in {
    val node = new FakeNode(overlap = 4L, need = 2L)
    runner.warmUp(node, List(t)).unsafeRunSync() shouldBe Right(())
    val all = node.batches.mkString("\n")
    all should include("AT (VERSION => 10) WHERE rowid IN")
    all should not include "t0_merged"
  }
