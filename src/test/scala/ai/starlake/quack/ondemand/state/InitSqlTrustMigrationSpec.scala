package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RoleDistribution, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import scala.util.Try

/** Liquibase 0053 on a control plane that already holds databases and pools: an empty initSql and a
  * database `qod serve` provisioned (its create audited with the actor `system`) come out trusted,
  * every other initSql untrusted. Needs the local Postgres of TestPostgres.
  */
class InitSqlTrustMigrationSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodist")

  private def exec(c: Connection)(sqls: String*): Unit =
    sqls.foreach { sql =>
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    }

  private def flags(c: Connection, table: String): Map[String, Boolean] =
    val st = c.createStatement()
    try
      val rs = st.executeQuery(s"SELECT name, init_sql_trusted FROM $table")
      val b  = Map.newBuilder[String, Boolean]
      while rs.next() do b += rs.getString(1) -> rs.getBoolean(2)
      b.result()
    finally st.close()

  "0053" should "trust only empty initSql and the databases qod serve provisioned" in {
    TestPostgres.ensureReachable()
    val dbName = s"qodist_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val c = store.jdbcDataSource.getConnection
        try
          exec(c)("INSERT INTO qodstate_tenant (id, display_name) VALUES ('acme', 'acme')")
          def db(id: String, name: String, sql: String) =
            TenantDb(id, "acme", name, TenantDbKind.InMemory, Map.empty, "", initSql = sql)
          // Written trusted: the upgrade must decide every flag itself.
          List(
            db("td-1", "acme_empty", ""),
            db("td-2", "acme_admin", "SET threads = 2;"),
            db("td-3", "acme_served", "CREATE VIEW v AS SELECT 1;"),
            db("td-4", "acme_served_then_edited", "CREATE VIEW v AS SELECT 2;")
          ).foreach(td => store.upsertTenantDb(td.copy(initSqlTrusted = true)))
          def pool(id: String, name: String, sql: String) =
            Pool(id, "acme", "td-1", name, 1, RoleDistribution(0, 0, 1), initSql = sql)
          List(pool("p-1", "bi_empty", ""), pool("p-2", "bi_admin", "SET threads = 2;"))
            .foreach(p => store.upsertPool(p.copy(initSqlTrusted = true)))
          exec(c)(
            // qod serve's boot hook, and a tenant admin's create of another database.
            "INSERT INTO qodstate_audit (ts, family, actor, actor_realm, tenant, action, target, " +
              "outcome, origin, detail) VALUES (now(), 'control-plane', 'system', 'system', " +
              "'acme', 'database.create', 'acme_served', 'ok', 'rest', '{}')",
            "INSERT INTO qodstate_audit (ts, family, actor, actor_realm, tenant, action, target, " +
              "outcome, origin, detail) VALUES (now(), 'control-plane', 'alice', 'acme', " +
              "'acme', 'database.create', 'acme_admin', 'ok', 'rest', '{}')",
            // qod serve made it, then a tenant admin changed it (review M2): not the manager's text.
            "INSERT INTO qodstate_audit (ts, family, actor, actor_realm, tenant, action, target, " +
              "outcome, origin, detail) VALUES (now() - interval '1 hour', 'control-plane', " +
              "'system', 'system', 'acme', 'database.create', 'acme_served_then_edited', 'ok', " +
              "'rest', '{}')",
            "INSERT INTO qodstate_audit (ts, family, actor, actor_realm, tenant, action, target, " +
              "outcome, origin, detail) VALUES (now(), 'control-plane', 'alice', 'acme', 'acme', " +
              "'database.update', 'acme_served_then_edited', 'ok', 'rest', '{}')",
            // Back to the state before 0053, then let Liquibase apply it: the upgrade path.
            "ALTER TABLE qodstate_tenant_db DROP COLUMN init_sql_trusted",
            "ALTER TABLE qodstate_pool DROP COLUMN init_sql_trusted",
            "ALTER TABLE qodstate_federated_source DROP COLUMN setup_sql_trusted",
            "DELETE FROM databasechangelog WHERE id LIKE '0053-%'"
          )
          new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
          flags(c, "qodstate_tenant_db") shouldBe Map(
            "acme_empty"              -> true,
            "acme_admin"              -> false,
            "acme_served"             -> true,
            "acme_served_then_edited" -> false
          )
          flags(c, "qodstate_pool") shouldBe Map("bi_empty" -> true, "bi_admin" -> false)
          // And the store reads the flag back.
          store.listTenantDbs("acme").map(td => td.name -> td.initSqlTrusted).toMap shouldBe
            Map(
              "acme_empty"              -> true,
              "acme_admin"              -> false,
              "acme_served"             -> true,
              "acme_served_then_edited" -> false
            )
        finally c.close()
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))
  }
