package ai.starlake.quack.ondemand.branch

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** Live spec (local Postgres + duckdb CLI): the Postgres-level clone of design section 2. */
class BranchClonerSpec extends AnyFlatSpec with Matchers with BranchLiveFixture:

  private val extra =
    """CREATE TABLE lake.tpch1.nation (n_nationkey INTEGER NOT NULL, n_name VARCHAR NOT NULL);
      |INSERT INTO lake.tpch1.nation VALUES (0, 'FR'), (1, 'DE');
      |CALL ducklake_flush_inlined_data('lake');
      |DELETE FROM lake.tpch1.region WHERE r_regionkey = 3;
      |INSERT INTO lake.tpch1.region VALUES (5, 'INLINED', 'f');
      |""".stripMargin

  "BranchCloner" should "copy the catalog at the parent head, rewrite paths and read the fork view" in
    withCatalog("brclone", extra) { (parent, parentDir) =>
      withBranchDb { (branchDb, branchDir) =>
        val fork = parent.maxSnapshotId().get
        val res  =
          BranchCloner(parentMeta).clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
        res.isRight shouldBe true
        val clone = res.toOption.get
        clone.forkSnapshot shouldBe fork
        clone.tablesCopied should be >= 20

        val br = branchReader(branchDb)
        try
          br.maxSnapshotId() shouldBe Some(fork)
          br.listSchemas().map(_.name) should contain("tpch1")
          br.listTables("tpch1").map(_.name).sorted shouldBe List("nation", "region")
          // Every copied data file points at the parent's directory, absolutely.
          val files = br.filesReferencedAt(fork)
          files should not be empty
          files.foreach { f =>
            f should startWith(parentDir.toString)
            Files.exists(java.nio.file.Path.of(f)) shouldBe true
          }
          br.filesScheduledForDeletion() shouldBe Nil
        finally br.close()

        // The branch reads the fork view: parent rows minus the deleted one plus the inlined one.
        val rows = duck(
          "SELECT r_regionkey FROM qod_branch.tpch1.region ORDER BY 1;",
          Some(branchDb),
          Some(branchDir)
        )
        rows shouldBe List("0", "1", "2", "4", "5")
        duck(
          "SELECT count(*) FROM qod_branch.tpch1.nation;",
          Some(branchDb),
          Some(branchDir)
        ) shouldBe
          List("2")
      }
    }

  it should "isolate writes: branch writes land under the branch prefix and the parent is untouched" in
    withCatalog("brisol", extra) { (parent, parentDir) =>
      withBranchDb { (branchDb, branchDir) =>
        BranchCloner(parentMeta)
          .clone(parentMeta("dbName"), branchDb, branchDir.toString + "/")
          .isRight shouldBe true
        val before = duck("SELECT r_regionkey, r_name FROM lake.tpch1.region ORDER BY 1;")
        duck(
          """UPDATE qod_branch.tpch1.region SET r_name = 'ASIA-EDITED' WHERE r_regionkey = 2;
            |DELETE FROM qod_branch.tpch1.region WHERE r_regionkey = 0;
            |INSERT INTO qod_branch.tpch1.region VALUES (6, 'NEW', 'g');
            |CALL ducklake_flush_inlined_data('qod_branch');""".stripMargin,
          Some(branchDb),
          Some(branchDir)
        )
        duck("SELECT r_regionkey, r_name FROM lake.tpch1.region ORDER BY 1;") shouldBe before
        duck(
          "SELECT r_regionkey, r_name FROM qod_branch.tpch1.region ORDER BY 1;",
          Some(branchDb),
          Some(branchDir)
        ) shouldBe List("1,AMERICA", "2,ASIA-EDITED", "4,MIDDLE EAST", "5,INLINED", "6,NEW")
        // New parquet exists only under the branch prefix; the parent prefix gained nothing.
        val parentFiles = Files.walk(parentDir).filter(p => p.toString.endsWith(".parquet")).count()
        val branchFiles = Files.walk(branchDir).filter(p => p.toString.endsWith(".parquet")).count()
        branchFiles should be >= 1L
        val parentAfter = parent.filesReferencedAt(parent.maxSnapshotId().get)
        parentAfter.foreach(f => f should not include "__br")
        parentFiles should be >= parentAfter.size.toLong
      }
    }

  it should "refuse a database without DuckLake metadata" in
    withCatalog("brempty") { (_, _) =>
      withBranchDb { (branchDb, branchDir) =>
        // Clone FROM the empty branch db (no ducklake_metadata) into itself: must fail cleanly.
        val res = BranchCloner(parentMeta).clone(branchDb, branchDb, branchDir.toString)
        res.isLeft shouldBe true
        res.left.toOption.get should include("no DuckLake metadata")
      }
    }
