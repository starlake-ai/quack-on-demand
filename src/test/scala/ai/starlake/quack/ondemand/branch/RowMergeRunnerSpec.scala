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
    val batches                                       = mutable.ListBuffer.empty[String]
    def exec(batch: String): IO[Either[String, Unit]] = IO {
      batches += batch
      if failOn.exists(batch.contains) then Left("boom") else Right(())
    }
    def rows(sql: String): IO[Either[String, List[List[Json]]]] = IO {
      Right(
        if sql.contains("t0_need") then List(List(Json.fromLong(overlap), Json.fromLong(need)))
        else if sql.contains("AS kind") then Nil
        else List(List.fill(12)(Json.fromLong(0L)))
      )
    }

  private def runner = new RowMergeRunner("lake", "qod_branch", 10L, None)

  "prepare" should "create the working schema and read the branch, with settings first" in {
    val node = new FakeNode(0L, 0L)
    runner.prepare(node, List(t)).unsafeRunSync() shouldBe Right(())
    node.batches.size shouldBe 1
    node.batches.head should startWith("SET dynamic_or_filter_threshold = 10000;\n")
    node.batches.head should include("""CREATE SCHEMA IF NOT EXISTS "memory"."qod_merge"""")
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

  it should "repeat the settings in every batch: each send is a new connection" in {
    val node = new FakeNode(overlap = 4L, need = 2L)
    runner.decide(node, List(t)).unsafeRunSync()
    node.batches.foreach(_ should startWith("SET dynamic_or_filter_threshold = 10000;\n"))
    node.batches.foreach(_ should endWith(";"))
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
