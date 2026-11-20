package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import scala.util.Try

/** Liquibase 0060 (#265) on a control plane where earlier tenant deletes left users behind: it
  * deletes the users whose tenant no longer exists, with what cascades from them, and keeps live
  * tenants' users and superusers. Needs the local Postgres of TestPostgres.
  */
class OrphanTenantUsersMigrationSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodotu")

  private def exec(c: Connection)(sqls: String*): Unit =
    sqls.foreach { sql =>
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    }

  private def strings(c: Connection, sql: String): List[String] =
    val st = c.createStatement()
    try
      val rs = st.executeQuery(sql)
      val b  = List.newBuilder[String]
      while rs.next() do b += rs.getString(1)
      b.result()
    finally st.close()

  "0060" should "delete the users of deleted tenants and keep every other user" in {
    TestPostgres.ensureReachable()
    val dbName = s"qodotu_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val c = store.jdbcDataSource.getConnection
        try
          exec(c)(
            // Tenant id and display name differ, as they do for a renamed tenant: the column holds
            // the id.
            "INSERT INTO qodstate_tenant (id, display_name) VALUES ('t-1', 'acme')",
            "INSERT INTO qodstate_user (id, tenant, username, password_hash) VALUES " +
              "('u-live', 't-1', 'alice', 'x'), ('u-gone', 'gone', 'bob', 'x'), " +
              "('u-root', NULL, 'root', 'x')",
            "INSERT INTO qodstate_group (id, tenant_id, name) VALUES ('g-1', 't-1', 'analysts')",
            "INSERT INTO qodstate_user_group (user_id, group_id) VALUES ('u-live', 'g-1')",
            // Back to the state before 0060, then let Liquibase apply it: the upgrade path.
            "DELETE FROM databasechangelog WHERE id = '0060-delete-orphan-tenant-users'"
          )
          new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()

          strings(c, "SELECT id FROM qodstate_user ORDER BY id") shouldBe List("u-live", "u-root")
          strings(c, "SELECT user_id FROM qodstate_user_group") shouldBe List("u-live")
        finally c.close()
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))
  }
