package ai.starlake.quack.edge.adapter

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EngineStatsSpec extends AnyFlatSpec with Matchers:

  "EngineStats.sql" should "run against a real DuckDB and decode to non-negative values" in:
    // TestArrow evaluates against the in-process DuckDB bundled by the JDBC driver, so this
    // pins duckdb_memory() / duckdb_temp_files() existing at the ABI we ship.
    val stats = EngineStats.fromReader(TestArrow.readerFor(EngineStats.sql))
    stats should not be empty
    val s = stats.get
    s.memoryUsedBytes should be >= 0L
    s.tempStorageBytes should be >= 0L
    s.spillFiles shouldBe 0L // a fresh in-memory connection has no spill files
    s.spillBytes shouldBe 0L

  "EngineStats.fromReader" should "return None on an empty result" in:
    EngineStats.fromReader(TestArrow.emptyReader()) shouldBe None

  it should "return None on a result with too few columns" in:
    EngineStats.fromReader(TestArrow.oneRowReader()) shouldBe None

  it should "return None on non-numeric cells" in:
    val reader = TestArrow.readerFor("SELECT 'a', 'b', 'c', 'd'")
    EngineStats.fromReader(reader) shouldBe None

  "EngineStatsTracker" should "store, overwrite, and remove samples per node" in:
    val t = new EngineStatsTracker
    t.snapshot("n1") shouldBe None
    t.update("n1", EngineStats(10L, 0L, 0L, 0L))
    t.snapshot("n1") shouldBe Some(EngineStats(10L, 0L, 0L, 0L))
    t.update("n1", EngineStats(20L, 5L, 1L, 99L))
    t.snapshot("n1") shouldBe Some(EngineStats(20L, 5L, 1L, 99L))
    t.remove("n1")
    t.snapshot("n1") shouldBe None
