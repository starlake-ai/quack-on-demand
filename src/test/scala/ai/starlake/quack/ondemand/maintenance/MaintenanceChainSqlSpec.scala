package ai.starlake.quack.ondemand.maintenance

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins F1's escaping contract: every interpolated string argument (alias, schema, table) must have
  * its single quotes doubled before reaching the SQL text, since these builders' output is executed
  * on a privileged node session holding metastore Postgres credentials. A scope like
  * `s.t'); anything--` must not be able to break out of the string literal.
  */
class MaintenanceChainSqlSpec extends AnyFlatSpec with Matchers:

  private val evilAlias  = "lake'; drop--"
  private val evilSchema = "s'; drop--"
  private val evilTable  = "t'); drop--"

  "flush" should "double a quote embedded in the alias" in {
    MaintenanceChainSql.flush(evilAlias) shouldBe
      "CALL ducklake_flush_inlined_data('lake''; drop--')"
  }

  "expireVersions" should "double a quote embedded in the alias" in {
    MaintenanceChainSql.expireVersions(evilAlias, List(1L, 2L)) shouldBe
      "CALL ducklake_expire_snapshots('lake''; drop--', versions => [1, 2])"
  }

  "mergeAdjacent" should "double a quote embedded in the alias" in {
    MaintenanceChainSql.mergeAdjacent(evilAlias) shouldBe
      "CALL ducklake_merge_adjacent_files('lake''; drop--')"
  }

  "rewriteTable" should "double quotes embedded in alias, table, and schema" in {
    MaintenanceChainSql.rewriteTable(evilAlias, evilSchema, evilTable) shouldBe
      "CALL ducklake_rewrite_data_files('lake''; drop--', 't''); drop--', schema => 's''; drop--')"
  }

  "cleanupOldFiles" should "double a quote embedded in the alias (both grace-day branches)" in {
    MaintenanceChainSql.cleanupOldFiles(evilAlias, graceDays = 0) shouldBe
      "CALL ducklake_cleanup_old_files('lake''; drop--', cleanup_all => true)"
    MaintenanceChainSql.cleanupOldFiles(evilAlias, graceDays = 7) should
      startWith("CALL ducklake_cleanup_old_files('lake''; drop--', older_than =>")
  }

  "deleteOrphans" should "double a quote embedded in the alias (both age branches)" in {
    MaintenanceChainSql.deleteOrphans(evilAlias, minAgeDays = 0) shouldBe
      "CALL ducklake_delete_orphaned_files('lake''; drop--', cleanup_all => true)"
    MaintenanceChainSql.deleteOrphans(evilAlias, minAgeDays = 3) should
      startWith("CALL ducklake_delete_orphaned_files('lake''; drop--', older_than =>")
  }
